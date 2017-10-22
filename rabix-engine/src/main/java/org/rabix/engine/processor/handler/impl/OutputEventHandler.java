package org.rabix.engine.processor.handler.impl;

import java.util.List;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Job.JobStatus;
import org.rabix.bindings.model.dag.DAGLinkPort.LinkPortType;
import org.rabix.common.functional.FunctionalHelper.Recursive;
import org.rabix.common.helper.InternalSchemaHelper;
import org.rabix.engine.JobHelper;
import org.rabix.engine.event.Event;
import org.rabix.engine.event.Event.EventType;
import org.rabix.engine.event.impl.InputUpdateEvent;
import org.rabix.engine.event.impl.JobStatusEvent;
import org.rabix.engine.event.impl.OutputUpdateEvent;
import org.rabix.engine.processor.EventProcessor;
import org.rabix.engine.processor.handler.EventHandler;
import org.rabix.engine.processor.handler.EventHandlerException;
import org.rabix.engine.service.IntermediaryFilesService;
import org.rabix.engine.service.JobRecordService;
import org.rabix.engine.service.JobService;
import org.rabix.engine.service.LinkRecordService;
import org.rabix.engine.service.VariableRecordService;
import org.rabix.engine.store.model.JobRecord;
import org.rabix.engine.store.model.JobRecord.JobState;
import org.rabix.engine.store.model.JobRecord.PortCounter;
import org.rabix.engine.store.model.LinkRecord;
import org.rabix.engine.store.model.VariableRecord;
import org.rabix.engine.store.model.scatter.ScatterStrategy;
import org.rabix.engine.store.model.scatter.ScatterStrategy.JobPortPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Handles {@link OutputUpdateEvent} events.
 */
public class OutputEventHandler implements EventHandler<OutputUpdateEvent> {

  @Inject
  private JobRecordService jobRecordService;
  @Inject
  private LinkRecordService linkService;
  @Inject
  private VariableRecordService variableService;
  @Inject
  private EventProcessor eventProcessor;
  @Inject
  private JobService jobService;
  @Inject
  private JobHelper jobHelper;
  @Inject
  private IntermediaryFilesService intermediaryFilesService;
  
  private Logger logger = LoggerFactory.getLogger(OutputEventHandler.class);
  
  public void handle(final OutputUpdateEvent event) throws EventHandlerException {
    logger.info(event.toString());
    JobRecord sourceJob = jobRecordService.find(event.getJobId(), event.getContextId());
    
    if (sourceJob.isScatterWrapper()) {
      jobRecordService.resetOutputPortCounter(sourceJob, event.getNumberOfScattered(), event.getPortId());
    }
    
    Boolean isScatterWrapper = sourceJob.isScatterWrapper();
    
    VariableRecord sourceVariable = variableService.find(event.getJobId(), event.getPortId(), LinkPortType.OUTPUT, event.getContextId());
    jobRecordService.decrementPortCounter(sourceJob, event.getPortId(), LinkPortType.OUTPUT);
    variableService.addValue(sourceVariable, event.getValue(), event.getPosition(), isScatterWrapper && !sourceJob.getScatterStrategy().isEmptyListDetected());
    variableService.update(sourceVariable); // TODO wha?
    jobRecordService.update(sourceJob);
    
    if (sourceJob.isCompleted()) {
      if(sourceJob.getOutputCounter(sourceVariable.getPortId()) != null) {
        if (sourceJob.isRoot() && sourceJob.isContainer()) {
          Job rootJob = createJob(sourceJob, JobHelper.transformStatus(sourceJob.getState()));
            eventProcessor.addToQueue(
                new JobStatusEvent(sourceJob.getId(), event.getContextId(), JobRecord.JobState.COMPLETED, rootJob.getOutputs(), event.getEventGroupId(), InternalSchemaHelper.ROOT_NAME));
          return;
        }
      }
    }
    
    Object value = variableService.getValue(sourceVariable);
    PortCounter outputCounter = sourceJob.getOutputCounter(event.getPortId());
    Integer numberOfScattered = outputCounter == null ? 0 : outputCounter.getGlobalCounter();

    if (isScatterWrapper) {
      numberOfScattered = sourceJob.getNumberOfGlobalOutputs();
      ScatterStrategy scatterStrategy = sourceJob.getScatterStrategy();
      if (scatterStrategy.isBlocking() || scatterStrategy.isEmptyListDetected()) {
        if (sourceJob.isOutputPortReady(event.getPortId())) {
          List<Object> valueStructure = scatterStrategy.valueStructure(sourceJob.getId(), event.getPortId(), event.getContextId());
          value = Recursive.make(jp -> {
            JobPortPair jobPair = (JobPortPair) jp;
            VariableRecord variableRecord = variableService.find(jobPair.getJobId(), jobPair.getPortId(), LinkPortType.OUTPUT, event.getContextId());
            return variableService.getValue(variableRecord);
          }).apply(valueStructure);
        } else {
          return;
        }
      }
    }
    
    List<LinkRecord> links = 
        linkService.findBySourceAndSourceType(sourceVariable.getJobId(), sourceVariable.getPortId(), LinkPortType.OUTPUT, event.getContextId());
    
    for (LinkRecord link : links) {
      Event newEvent = createChildEvent(event, sourceJob, numberOfScattered, link, value);
      if (newEvent != null) {
        eventProcessor.send(newEvent);
      }
    }
    
    if (links.isEmpty()) {
      intermediaryFilesService.handleDanglingOutput(event.getContextId(), event.getValue());
    }
  }

  private Event createChildEvent(final OutputUpdateEvent event, JobRecord sourceJob, Integer numberOfScattered, LinkRecord link, Object tempValue) {
    switch (link.getDestinationVarType()) {
      case INPUT:
        boolean lookAhead = false;
        JobRecord destinationJob = jobRecordService.find(link.getDestinationJobId(), link.getRootId());
        int position = link.getPosition();
        if (sourceJob.isScatterWrapper()) {
          if (destinationJob.isScatterPort(link.getDestinationJobPort()) && !destinationJob.isBlocking()
              && !(destinationJob.getInputPortIncoming(event.getPortId()) > 1)) {
            tempValue = event.getValue();
            position = event.getPosition();
            lookAhead = true;
          } else {
            if (!sourceJob.isOutputPortReady(event.getPortId())) {
              return null;
            }
          }
        }
        return new InputUpdateEvent(event.getContextId(), link.getDestinationJobId(), link.getDestinationJobPort(), tempValue, lookAhead,
            numberOfScattered, position, event.getEventGroupId(), event.getProducedByNode());

      case OUTPUT:
        boolean destinationRoot = link.getDestinationJobId().equals(InternalSchemaHelper.ROOT_NAME);
        if (sourceJob.isScattered() && destinationRoot)
          return null;
        if (sourceJob.isOutputPortReady(event.getPortId()) || sourceJob.isScattered()) {
          return new OutputUpdateEvent(event.getContextId(), link.getDestinationJobId(), link.getDestinationJobPort(), tempValue, numberOfScattered,
              link.getPosition(), event.getEventGroupId(), event.getProducedByNode());
        }
    }
    return null;
  }  
  
  private Job createJob(JobRecord record, JobStatus status){
    try {
      return jobHelper.createJob(record, status);
    } catch (BindingException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }
}