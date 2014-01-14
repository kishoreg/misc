package com.example.spdy.utils;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

public class MiscUtils
{
  public static void configureConsole()
  {
    ConsoleAppender console = new ConsoleAppender();
    PatternLayout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n");
    console.setLayout(layout);
    console.activateOptions();
    Logger.getRootLogger().addAppender(console);
  }
}
