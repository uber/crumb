package com.uber.crumb.annotations.internal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * An annotation for recording information about a given index in crumb. Should be considered
 * private API.
 */
@Retention(RetentionPolicy.CLASS)
@Target(TYPE)
public @interface CrumbIndex {
  String value();
}
