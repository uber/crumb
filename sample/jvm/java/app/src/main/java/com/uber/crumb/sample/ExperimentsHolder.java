package com.uber.crumb.sample;

import com.uber.crumb.annotations.CrumbConsumer;
import com.uber.crumb.sample.experimentsenumscompiler.annotations.Experiments;
import java.util.List;
import java.util.Map;

@CrumbConsumer
@Experiments
public abstract class ExperimentsHolder {

  public static Map<Class, List<String>> experiments() {
    return Experiments_ExperimentsHolder.EXPERIMENTS;
  }

}
