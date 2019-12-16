package org.rabix.engine.test;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.rabix.bindings.model.ApplicationPort;
import org.rabix.bindings.model.LinkMerge;
import org.rabix.bindings.model.PickValue;
import org.rabix.bindings.model.dag.DAGLinkPort;

/**
 * Created by luka on 23.1.17..
 */
public class TestPort extends ApplicationPort {

  public TestPort(@JsonProperty("id") String id,
                  @JsonProperty("default") Object defaultValue,
                  @JsonProperty("type") Object schema,
                  @JsonProperty("scatter") Boolean scatter,
                  @JsonProperty("linkMerge") String linkMerge,
                  @JsonProperty("pickValue") String pickValue,
                  @JsonProperty("description") String description) {
    super(id, defaultValue, schema, scatter, linkMerge, pickValue, description);
  }

  @Override
  public boolean isList() {
    return schema.equals("vector");
  }

  public static TestPort simplePort(String id) {
    return new TestPort(id, null, "type", false, null, null, null);
  }

  public DAGLinkPort toInputPort(String appId) {
    String fullId = appId + "." + id;
    LinkMerge lm = linkMerge != null? LinkMerge.valueOf(linkMerge) : null;
    PickValue pv = pickValue != null? PickValue.valueOf(pickValue) : null;
    return new DAGLinkPort(fullId, fullId, DAGLinkPort.LinkPortType.INPUT, lm, pv, scatter, defaultValue, null);
  }

  public DAGLinkPort toOutputPort(String appId) {
    String fullId = appId + "." + id;
    LinkMerge lm = linkMerge != null? LinkMerge.valueOf(linkMerge) : null;
    PickValue pv = pickValue != null? PickValue.valueOf(pickValue) : null;
    return new DAGLinkPort(fullId, fullId, DAGLinkPort.LinkPortType.OUTPUT, lm, pv, scatter, defaultValue, null);
  }

  @Override
  public Object getBinding() {
    // TODO Auto-generated method stub
    return null;
  }

}
