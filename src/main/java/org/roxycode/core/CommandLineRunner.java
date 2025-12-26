package org.roxycode.core;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(CommandLineRunner.class);

    public void run() {
        LOG.info("CommandLineRunner started.");
    }
}