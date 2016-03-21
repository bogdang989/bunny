package org.rabix.bindings.model;

import org.rabix.bindings.BindingException;
import org.rabix.bindings.model.dag.DAGNode;
import org.rabix.common.helper.EncodingHelper;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Executable {

  public static enum ExecutableStatus {
    PENDING,
    READY,
    STARTED,
    ABORTED,
    FAILED,
    COMPLETED,
    RUNNING
  }

  @JsonProperty("id")
  private final String id;
  @JsonProperty("nodeId")
  private final String nodeId;
  @JsonProperty("app")
  private final String app;
  @JsonProperty("status")
  private final ExecutableStatus status;
  @JsonProperty("context")
  private final Context context;
  @JsonProperty("inputs")
  private final Object inputs;
  @JsonProperty("outputs")
  private final Object outputs;
  @JsonProperty("allocatedResources")
  private final Resources allocatedResources;
  
  public Executable(String id, String nodeId, DAGNode node, ExecutableStatus status, Object inputs, Resources allocatedResources, Context context) {
    this.id = id;
    this.nodeId = nodeId;
    this.status = status;
    this.inputs = inputs;
    this.outputs = null;
    this.context = context;
    this.allocatedResources = allocatedResources;
    this.app = EncodingHelper.encodeBase64(node.getApp());
  }

  @JsonCreator
  public Executable(@JsonProperty("id") String id, 
      @JsonProperty("nodeId") String nodeId,
      @JsonProperty("app") String app, 
      @JsonProperty("status") ExecutableStatus status, 
      @JsonProperty("allocatedResources") Resources allocatedResources,
      @JsonProperty("inputs") Object inputs, 
      @JsonProperty("outputs") Object otputs,
      @JsonProperty("context") Context context) {
    this.id = id;
    this.nodeId = nodeId;
    this.app = app;
    this.status = status;
    this.inputs = inputs;
    this.outputs = otputs;
    this.context = context;
    this.allocatedResources = allocatedResources;
  }

  public static Executable cloneWithResources(Executable executable, Resources resources) {
    return new Executable(executable.id, executable.nodeId, executable.app, executable.status, resources, executable.inputs, executable.outputs, executable.context);
  }

  public static Executable cloneWithStatus(Executable executable, ExecutableStatus status) {
    return new Executable(executable.id, executable.nodeId, executable.app, status, executable.allocatedResources, executable.inputs, executable.outputs, executable.context);
  }
  
  public static Executable cloneWithInputs(Executable executable, Object inputs) {
    return new Executable(executable.id, executable.nodeId, executable.app, executable.status, executable.allocatedResources, inputs, executable.outputs, executable.context);
  }
  
  public static Executable cloneWithOutputs(Executable executable, Object outputs) {
    return new Executable(executable.id, executable.nodeId, executable.app, executable.status, executable.allocatedResources, executable.inputs, outputs, executable.context);
  }
  
  public String getId() {
    return id;
  }
  
  public String getNodeId() {
    return nodeId;
  }
  
  @JsonIgnore
  public <T> T getApp(Class<T> clazz) {
    return EncodingHelper.decodeBase64(app, clazz);
  }
  
  public Resources getAllocatedResources() {
    return allocatedResources;
  }
  
  public Object getInputs() {
    return inputs;
  }
  
  @JsonIgnore
  public <T> T getInputs(Class<T> clazz) throws BindingException {
    if (inputs == null) {
      return null;
    }
    if (clazz.isInstance(inputs)) {
      return clazz.cast(inputs);
    }
    throw new BindingException("Invalid Executable inputs section. Inputs: " + inputs);
  }
  
  public Object getOutputs() {
    return outputs;
  }
  
  @JsonIgnore
  public <T> T getOutputs(Class<T> clazz) throws BindingException {
    if (outputs == null) {
      return null;
    }
    if (clazz.isInstance(outputs)) {
      return clazz.cast(outputs);
    }
    throw new BindingException("Invalid Executable outputs section. Outputs: " + inputs);
  }
  
  public ExecutableStatus getStatus() {
    return status;
  }
  
  public Context getContext() {
    return context;
  }
  
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((context == null) ? 0 : context.getId().hashCode());
    result = prime * result + ((nodeId == null) ? 0 : nodeId.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Executable other = (Executable) obj;
    if (context == null) {
      if (other.context != null)
        return false;
    } else if (!context.getId().equals(other.context.getId()))
      return false;
    if (nodeId == null) {
      if (other.nodeId != null)
        return false;
    } else if (!nodeId.equals(other.nodeId))
      return false;
    return true;
  }

  @Override
  public String toString() {
    return "Executable [id=" + id + ", nodeId=" + nodeId + ", status=" + status + ", context=" + context + ", inputs=" + inputs + ", outputs=" + outputs + ", allocatedResources=" + allocatedResources + "]";
  }
}
