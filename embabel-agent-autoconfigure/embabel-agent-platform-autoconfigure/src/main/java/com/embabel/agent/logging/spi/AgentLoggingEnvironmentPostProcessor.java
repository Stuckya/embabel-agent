/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.logging.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Collections;
import java.util.Properties;

/**
 * Environment post-processor that automatically configures logging for Embabel Agent library.
 * <p>
 * This processor selects the library's default logging configuration to match the active
 * logging system — {@code logback-embabel.xml} for Logback, {@code log4j2-embabel.xml} for
 * Log4j2 — unless the user has provided their own logging configuration via:
 * <ul>
 *   <li>{@code logging.config} property</li>
 *   <li>{@code logback-spring.xml} file in their application</li>
 *   <li>{@code log4j2-spring.xml} or {@code log4j2.xml} file in their application</li>
 * </ul>
 * <p>
 * The two defaults are at parity (same console pattern, same level suppressions), so Log4j2
 * applications get the same out-of-the-box experience as Logback applications. If neither
 * logging system is on the classpath, the processor applies nothing.
 * <p>
 * The processor reads the relevant config path ({@code embabel.agent.platform.logging.config}
 * or {@code embabel.agent.platform.logging.log4j2-config}) directly from the
 * {@code agent-platform.properties} file and adds it to the Spring Environment as
 * {@code logging.config} for Spring Boot's logging system to use during initialization.
 * <p>
 * <b>Separation of Concerns:</b> This processor only reads the properties file to obtain
 * the logging configuration path. It does NOT load properties into the Spring Environment.
 * The {@code AgentPlatformPropertiesLoader} (in embabel-agent-api) is responsible for loading
 * {@code agent-platform.properties} into the Environment for {@code @ConfigurationProperties} binding.
 *
 * @see EnvironmentPostProcessor
 * @see com.embabel.agent.spi.config.spring.AgentPlatformPropertiesLoader
 */
public class AgentLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(AgentLoggingEnvironmentPostProcessor.class);

    private static final String LOGGING_CONFIG_PROPERTY = "logging.config";
    private static final String LOGBACK_SPRING_XML = "logback-spring.xml";
    private static final String LOG4J2_SPRING_XML = "log4j2-spring.xml";
    private static final String LOG4J2_XML = "log4j2.xml";
    private static final String LOGBACK_CONTEXT_CLASS = "ch.qos.logback.classic.LoggerContext";
    private static final String LOG4J2_CONTEXT_CLASS = "org.apache.logging.log4j.core.LoggerContext";
    private static final String AGENT_PLATFORM_PROPERTIES = "agent-platform.properties";
    private static final String EMBABEL_LOGGING_CONFIG_PROPERTY = "embabel.agent.platform.logging.config";
    private static final String EMBABEL_LOG4J2_CONFIG_PROPERTY = "embabel.agent.platform.logging.log4j2-config";

    /**
     * Post-processes the environment to configure library logging if not already configured by the user.
     * <p>
     * Processing steps:
     * <ol>
     *   <li>Check if user has set {@code logging.config} property - if yes, skip</li>
     *   <li>Check if user has {@code logback-spring.xml} in classpath - if yes, skip</li>
     *   <li>Check if user has {@code log4j2-spring.xml} or {@code log4j2.xml} in classpath - if yes, skip</li>
     *   <li>Select the default config for the active logging system (Logback or Log4j2)</li>
     *   <li>Set {@code logging.config} to the selected default, or skip if neither system is present</li>
     * </ol>
     * <p>
     * <b>Note:</b> This processor reads {@code agent-platform.properties} as a simple resource file
     * without loading it into the Spring Environment. The {@code AgentPlatformPropertiesLoader}
     * (in embabel-agent-api) is responsible for loading properties into the Environment for
     * {@code @ConfigurationProperties} binding.
     *
     * @param environment the Spring environment
     * @param application the Spring application
     */
    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {

        log.debug("AgentLoggingEnvironmentPostProcessor invoked");

        // 1. If user explicitly sets logging.config → do nothing
        if (environment.containsProperty(LOGGING_CONFIG_PROPERTY)) {
            log.debug("User-defined logging.config detected — skipping library logging");
            return;
        }

        // 2. If user provides their own logback-spring.xml → do nothing
        if (resourceExists(LOGBACK_SPRING_XML)) {
            log.debug("Application logback-spring.xml detected — skipping library logging");
            return;
        }

        // 3. If user provides their own log4j2 config → do nothing
        if (resourceExists(LOG4J2_SPRING_XML) || resourceExists(LOG4J2_XML)) {
            log.debug("Application log4j2 configuration detected — skipping library logging");
            return;
        }

        // 4. Select the default that matches the active logging system
        String loggingConfigPath = selectDefaultLoggingConfig();
        if (loggingConfigPath == null) {
            log.debug("No supported logging system on the classpath — skipping library logging");
            return;
        }

        log.debug("Setting logging.config to {}", loggingConfigPath);
        // Add to Environment with highest priority so Spring Boot's LoggingApplicationListener can read it
        environment.getPropertySources().addFirst(
                new MapPropertySource("loggingConfigSource",
                        Collections.singletonMap(LOGGING_CONFIG_PROPERTY, loggingConfigPath)));
    }

    /**
     * Selects the default logging config matching the active logging system, mirroring
     * Spring Boot's own precedence (Logback wins when present, then Log4j2).
     *
     * @return the config path for the active logging system, or null if neither is on the classpath
     */
    private String selectDefaultLoggingConfig() {
        if (classPresent(LOGBACK_CONTEXT_CLASS)) {
            return readConfigFromProperties(EMBABEL_LOGGING_CONFIG_PROPERTY);
        }
        if (classPresent(LOG4J2_CONTEXT_CLASS)) {
            return readConfigFromProperties(EMBABEL_LOG4J2_CONFIG_PROPERTY);
        }
        return null;
    }

    /**
     * Reads a logging configuration path from {@code agent-platform.properties} file.
     * <p>
     * This method reads the properties file as a simple resource without loading it into
     * the Spring Environment. The {@code AgentPlatformPropertiesLoader} (in embabel-agent-api)
     * handles loading properties into the Environment for {@code @ConfigurationProperties} binding.
     *
     * @param key the property holding the config path
     * @return the config path, or null if absent or blank
     */
    private String readConfigFromProperties(String key) {
        Properties properties = loadAgentPlatformProperties();
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            log.warn("{} not found in {} — library logging disabled", key, AGENT_PLATFORM_PROPERTIES);
            return null;
        }
        return value;
    }

    private Properties loadAgentPlatformProperties() {
        Resource resource = agentPlatformPropertiesResource();
        if (!resource.exists()) {
            log.warn("{} not found on classpath", AGENT_PLATFORM_PROPERTIES);
            return null;
        }
        try (var inputStream = resource.getInputStream()) {
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties;
        } catch (IOException e) {
            log.error("Failed to read {}", AGENT_PLATFORM_PROPERTIES, e);
            return null;
        }
    }

    /**
     * Resolves the {@code agent-platform.properties} resource.
     *
     * @return the classpath resource holding the platform properties
     */
    protected Resource agentPlatformPropertiesResource() {
        return new ClassPathResource(AGENT_PLATFORM_PROPERTIES);
    }

    /**
     * Checks if a resource exists on the classpath.
     *
     * @param name the resource name to check
     * @return {@code true} if the resource exists, {@code false} otherwise
     */
    protected boolean resourceExists(String name) {
        return getClass().getClassLoader().getResource(name) != null;
    }

    /**
     * Checks if a class is present on the classpath without initializing it.
     *
     * @param className the fully qualified class name to check
     * @return {@code true} if the class is present, {@code false} otherwise
     */
    protected boolean classPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Returns the order for this post-processor.
     * <p>
     * Uses {@link Ordered#HIGHEST_PRECEDENCE} to run as early as possible,
     * ensuring {@code logging.config} is set before Spring Boot's logging
     * system initializes.
     *
     * @return the order value (highest precedence)
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
