package org.slf4j; public interface Logger { void info(String s,Object... a); void warn(String s,Object... a); void error(String s,Throwable t); }
