package org.rabix.executor.container.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.CommandLine;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Resources;
import org.rabix.bindings.model.requirement.EnvironmentVariableRequirement;
import org.rabix.bindings.model.requirement.Requirement;
import org.rabix.common.logging.VerboseLogger;
import org.rabix.executor.config.StorageConfiguration;
import org.rabix.executor.container.ContainerException;
import org.rabix.executor.container.ContainerHandler;
import org.rabix.executor.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalContainerHandler implements ContainerHandler {

  private final static Logger logger = LoggerFactory.getLogger(LocalContainerHandler.class);

  private Job job;
  private File workingDir;

  private Future<Integer> processFuture;
  private ExecutorService executorService = Executors.newSingleThreadExecutor();

  private Process process;
  private String commandLineString;
  
  public static final String HOME_ENV_VAR = "HOME";
  public static final String TMPDIR_ENV_VAR = "TMPDIR";

  public LocalContainerHandler(Job job, StorageConfiguration storageConfig) {
    this.job = job;
    this.workingDir = storageConfig.getWorkingDir(job);
  }

  @Override
  public synchronized void start() throws ContainerException {
    try {
      VerboseLogger.log(String.format("Local execution (no container) has started"));
      
      Bindings bindings = BindingsFactory.create(job);

      CommandLine commandLine = bindings.buildCommandLineObject(job, workingDir, new FilePathMapper() {
        @Override
        public String map(String path, Map<String, Object> config) throws FileMappingException {
          return path;
        }
      });

      commandLineString = commandLine.build();

      final ProcessBuilder processBuilder = new ProcessBuilder();
      List<Requirement> combinedRequirements = new ArrayList<>();
      combinedRequirements.addAll(bindings.getHints(job));
      combinedRequirements.addAll(bindings.getRequirements(job));
      
      Map<String, String> env = processBuilder.environment();
      Resources resources = job.getResources();
      if(resources != null) {
        if(resources.getWorkingDir() != null) {
          env.put(HOME_ENV_VAR, resources.getWorkingDir());
        }
        if(resources.getTmpDir() != null) {
          env.put(TMPDIR_ENV_VAR, resources.getTmpDir());
        }
      }
      
      EnvironmentVariableRequirement environmentVariableResource = getRequirement(combinedRequirements, EnvironmentVariableRequirement.class);
      if (environmentVariableResource != null) {
        for (Entry<String, String> envVariableEntry : environmentVariableResource.getVariables().entrySet()) {
          env.put(envVariableEntry.getKey(), envVariableEntry.getValue());
        }
      }

      processBuilder.directory(workingDir);
      List<String> parts = commandLine.getParts();
      boolean overrideShell = false;
      if(StringUtils.startsWithAny(parts.get(0), "/bin/bash", "/bin/sh")){
        parts.remove(0);
        if(parts.get(0).startsWith("-c")){
          parts.remove(0);
        }
        overrideShell=true;
      }
      
      String cmdString = StringUtils.join(parts, " ");
      overrideShell = overrideShell || cmdString.contains("&&") || cmdString.contains("|") || cmdString.contains(">");

      if (commandLine.isRunInShell() || overrideShell) {
        processBuilder.command("/bin/sh", "-c", cmdString);
      } else {
        String[] commandLineArray = cmdString.split(" ");
        processBuilder.command(commandLineArray);
      }
      redirect(processBuilder, workingDir, commandLine);;
      
      VerboseLogger.log(String.format("Running command line: %s", cmdString));
      processFuture = executorService.submit(new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
          process = processBuilder.start();
          process.waitFor();
//          List readLines = IOUtils.readLines(process.getErrorStream());IOUtils.readLines(process.getInputStream());
          return process.exitValue();
        }
      });
      logger.info("Local container has started.");
    } catch (Exception e) {
      logger.error("Failed to start application", e);
      throw new ContainerException("Failed to start application", e);
    }
  }
  
  private void redirect(ProcessBuilder pb, File workingDir, CommandLine commandLine) {
    String stdIn = commandLine.getStandardIn();
    String stdOut = commandLine.getStandardOut();
    String stdError = commandLine.getStandardError();
    Path path = workingDir.toPath();
    if (!StringUtils.isEmpty(stdIn))
      pb.redirectInput(ProcessBuilder.Redirect.from(path.resolve(stdIn).toFile()));
    if (!StringUtils.isEmpty(stdOut))
      pb.redirectOutput(ProcessBuilder.Redirect.to(path.resolve(stdOut).toFile()));
    if (!StringUtils.isEmpty(stdError))
      pb.redirectError(ProcessBuilder.Redirect.to(path.resolve(stdError).toFile()));
  }

  @SuppressWarnings("unchecked")
  private <T extends Requirement> T getRequirement(List<Requirement> requirements, Class<T> clazz) {
    for (Requirement requirement : requirements) {
      if (requirement.getClass().equals(clazz)) {
        return (T) requirement;
      }
    }
    return null;
  }

  @Override
  public synchronized void stop() throws ContainerException {
    if (processFuture == null) {
      return;
    }
    processFuture.cancel(true);
  }

  @Override
  public synchronized boolean isStarted() throws ContainerException {
    return processFuture != null;
  }

  @Override
  public synchronized boolean isRunning() throws ContainerException {
    if (processFuture == null) {
      return false;
    }
    return !processFuture.isDone();
  }

  @Override
  public synchronized int getProcessExitStatus() throws ContainerException {
    try {
      return processFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new ContainerException(e);
    }
  }

  @Override
  public synchronized void dumpContainerLogs(File errorFile) throws ContainerException {

    try {
      if (!errorFile.exists()) {
        errorFile.createNewFile();
      }
      if (process == null) {
        try (Writer outputStream = new FileWriter(errorFile)) {
          outputStream.write("Process not initiated");
        }
      }
      try (InputStream inputStream = process.getErrorStream(); OutputStream outputStream = new FileOutputStream(errorFile)) {
        IOUtils.copy(inputStream, outputStream);
      }
    } catch (IOException e) {
      logger.error("Failed to create " + errorFile.getName(), e);
      throw new ContainerException("Failed to create " + errorFile.getName(), e);
    }
  }

  @Override
  public void dumpCommandLine() throws ContainerException {
    try {
      File commandLineFile = new File(workingDir, JobHandler.COMMAND_LOG);
      FileUtils.writeStringToFile(commandLineFile, commandLineString);
    } catch (IOException e) {
      logger.error("Failed to dump command line into " + JobHandler.COMMAND_LOG);
      throw new ContainerException(e);
    }
  }

  @Override
  public void removeContainer() {
  }
}
