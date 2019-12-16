package org.rabix.bindings.model;

public enum PickValue {
  first_non_null,
  exactly_one_non_null,
  all_non_null;
  
  public static boolean isBlocking(PickValue pickValue) {
    switch (pickValue) {
    case first_non_null:
      return true;
    case exactly_one_non_null:
      return true;
    case all_non_null:
      return true;
    default:
      return true;
    }
  }
}
