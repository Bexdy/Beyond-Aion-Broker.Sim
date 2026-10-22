package org.slf4j; public class LoggerFactory { static Logger mk(){ return new Logger(){
 String f(String s,Object[] a){ int i=0; StringBuilder b=new StringBuilder(); for(int k=0;k<s.length();k++){ if(s.startsWith("{}",k)&&i<a.length){b.append(a[i++]);k++;} else b.append(s.charAt(k)); } return b.toString(); }
 public void info(String s,Object... a){System.out.println("INFO  "+f(s,a));} public void warn(String s,Object... a){System.out.println("WARN  "+f(s,a));} public void error(String s,Throwable t){System.out.println("ERROR "+s); t.printStackTrace(System.out);} }; }
 public static Logger getLogger(Class<?> c){return mk();} public static Logger getLogger(String c){return mk();} }
