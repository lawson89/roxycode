package org.roxycode.app.ai;

@FunctionalInterface
public interface JexlExecutionListener {
  void onJexlExecuted(JexlExecutionEvent event);
}