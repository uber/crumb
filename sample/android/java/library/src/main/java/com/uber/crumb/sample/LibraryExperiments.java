package com.uber.crumb.sample;

import com.uber.crumb.annotations.CrumbProducer;
import com.uber.crumb.sample.experimentsenumscompiler.annotations.Experiments;

@CrumbProducer
@Experiments
public enum LibraryExperiments {
  XP_A,
  XP_C,
  XP_D,
  XP_E,
  XP_F,
  XP_G,
}
