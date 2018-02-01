package com.uber.crumb.sample.experimentsenumscompiler.annotations;

import com.uber.crumb.annotations.CrumbQualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

@CrumbQualifier
@Retention(CLASS)
@Target(TYPE)
public @interface Experiments {}
