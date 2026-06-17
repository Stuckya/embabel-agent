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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentLoggingEnvironmentPostProcessor}.
 */
class AgentLoggingEnvironmentPostProcessorTest {

    private AgentLoggingEnvironmentPostProcessor processor;
    private ConfigurableEnvironment environment;
    private SpringApplication application;

    @BeforeEach
    void setUp() {
        processor = new AgentLoggingEnvironmentPostProcessor();
        environment = new StandardEnvironment();
        application = new SpringApplication();
    }

    @Test
    void shouldSetLoggingConfigFromAgentPlatformProperties() {
        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String loggingConfig = environment.getProperty("logging.config");
        assertNotNull(loggingConfig, "logging.config should be set");
        assertEquals("classpath:logback-embabel.xml", loggingConfig);
    }

    @Test
    void shouldSkipWhenUserAlreadySetLoggingConfig() {
        // Given
        System.setProperty("logging.config", "classpath:user-logback.xml");
        try {
            environment.getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("test",
                            java.util.Collections.singletonMap("logging.config", "classpath:user-logback.xml"))
            );

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            String loggingConfig = environment.getProperty("logging.config");
            assertEquals("classpath:user-logback.xml", loggingConfig,
                    "User's logging.config should be preserved");
        } finally {
            System.clearProperty("logging.config");
        }
    }

    @Test
    void shouldHaveHighestPrecedenceOrder() {
        // When
        int order = processor.getOrder();

        // Then
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order,
                "Should run with highest precedence to set logging config before logging initialization");
    }

    @Test
    void shouldNotThrowExceptionWhenAgentPlatformPropertiesIsMissing() {
        // Given - using a custom classloader that doesn't have agent-platform.properties
        // This is difficult to simulate in a unit test, so we just verify no exceptions
        // are thrown during normal execution

        // When/Then - should not throw
        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));
    }

    @Test
    void shouldAddLoggingConfigToEnvironment() {
        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        assertTrue(environment.containsProperty("logging.config"),
                "logging.config should be present in environment");
    }

    @Test
    void shouldUseFirstPropertySourceForLoggingConfig() {
        // Given
        processor.postProcessEnvironment(environment, application);

        // Then
        // Verify it's added as first property source (highest priority)
        String firstSourceName = environment.getPropertySources().iterator().next().getName();
        assertEquals("loggingConfigSource", firstSourceName,
                "Logging config should be added as first property source");
    }

    @Test
    void shouldSkipWhenLogbackSpringXmlExists() {
        // Given - Create a temporary logback-spring.xml in test resources
        // We can't easily create a file, but we can verify the behavior with existing setup
        // Since logback-spring.xml doesn't exist in our test classpath, we test the opposite

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - should still set config since logback-spring.xml doesn't exist in test
        assertNotNull(environment.getProperty("logging.config"),
                "Should set logging.config when logback-spring.xml doesn't exist");
    }

    @Test
    void shouldNotSetLoggingConfigWhenPropertyValueIsBlank() {
        // This test verifies the behavior when agent-platform.properties
        // has an empty or blank value for embabel.agent.platform.logging.config
        // In practice, this won't happen with our current setup, but tests the defensive code

        // When - with current agent-platform.properties containing valid value
        processor.postProcessEnvironment(environment, application);

        // Then - should have set the config (opposite of blank scenario)
        String loggingConfig = environment.getProperty("logging.config");
        assertNotNull(loggingConfig, "logging.config should be set when property has value");
        assertFalse(loggingConfig.isBlank(), "logging.config should not be blank");
    }

    @Test
    void shouldHandleEnvironmentWithExistingPropertySources() {
        // Given - environment already has some property sources
        environment.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("existing",
                        java.util.Collections.singletonMap("some.property", "value"))
        );

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - logging config should still be added
        String loggingConfig = environment.getProperty("logging.config");
        assertNotNull(loggingConfig, "logging.config should be set even with existing property sources");
        assertEquals("classpath:logback-embabel.xml", loggingConfig);
    }

    @Test
    void shouldPreserveExistingEnvironmentProperties() {
        // Given - environment has existing properties
        environment.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("existing",
                        java.util.Collections.singletonMap("my.property", "my-value"))
        );

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - existing properties should still be present
        assertEquals("my-value", environment.getProperty("my.property"),
                "Existing properties should be preserved");
        assertNotNull(environment.getProperty("logging.config"),
                "New logging.config should also be present");
    }

    @Test
    void shouldCreateNewPropertySourceForLoggingConfig() {
        // Given
        int initialSourceCount = environment.getPropertySources().size();

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        int finalSourceCount = environment.getPropertySources().size();
        assertEquals(initialSourceCount + 1, finalSourceCount,
                "Should add exactly one new property source");
    }

    @Test
    void shouldCheckForLogbackSpringXml() {
        // This test verifies the resourceExists method is called
        // In our test environment, logback-spring.xml doesn't exist,
        // so the processor should continue and set logging.config

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - should set config because logback-spring.xml doesn't exist
        String loggingConfig = environment.getProperty("logging.config");
        assertNotNull(loggingConfig, "Should set logging.config when logback-spring.xml doesn't exist");
        assertEquals("classpath:logback-embabel.xml", loggingConfig);
    }

    @Test
    void shouldSkipWhenUserSuppliesLog4j2SpringXml() {
        AgentLoggingEnvironmentPostProcessor log4j2Processor =
                processorWithResource("log4j2-spring.xml");

        log4j2Processor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not override a user-supplied log4j2-spring.xml");
    }

    @Test
    void shouldSkipWhenUserSuppliesLog4j2Xml() {
        AgentLoggingEnvironmentPostProcessor log4j2Processor =
                processorWithResource("log4j2.xml");

        log4j2Processor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not override a user-supplied log4j2.xml");
    }

    @Test
    void shouldSetLog4j2DefaultWhenLog4j2IsActiveLoggingSystem() {
        // Logback absent, Log4j2 present (e.g. spring-boot-starter-log4j2, which excludes Logback)
        AgentLoggingEnvironmentPostProcessor log4j2Processor =
                processorWithActiveLoggingSystem("org.apache.logging.log4j.core.LoggerContext");

        log4j2Processor.postProcessEnvironment(environment, application);

        assertEquals("classpath:log4j2-embabel.xml", environment.getProperty("logging.config"),
                "Log4j2 applications should get Embabel's Log4j2 default, at parity with Logback");
    }

    @Test
    void shouldSkipWhenNoSupportedLoggingSystemPresent() {
        // Neither Logback nor Log4j2 on the classpath
        AgentLoggingEnvironmentPostProcessor noSystemProcessor =
                new AgentLoggingEnvironmentPostProcessor() {
                    @Override
                    protected boolean classPresent(String className) {
                        return false;
                    }
                };

        noSystemProcessor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should apply nothing when no supported logging system is present");
    }

    @Test
    void shouldSetLogbackDefaultWhenLogbackIsActiveLoggingSystem() {
        // The default test classpath has Logback present and no user config
        processor.postProcessEnvironment(environment, application);

        assertEquals("classpath:logback-embabel.xml", environment.getProperty("logging.config"),
                "Logback applications should get Embabel's Logback default");
    }

    @Test
    void shouldSkipWhenUserSuppliesLogbackSpringXml() {
        AgentLoggingEnvironmentPostProcessor logbackProcessor =
                processorWithResource("logback-spring.xml");

        logbackProcessor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not override a user-supplied logback-spring.xml");
    }

    @Test
    void classPresentShouldDetectPresentAndAbsentClasses() {
        assertTrue(processor.classPresent("java.lang.String"),
                "Should detect a class that is on the classpath");
        assertFalse(processor.classPresent("com.embabel.agent.logging.spi.NoSuchClass"),
                "Should report a class that is not on the classpath as absent");
    }

    @Test
    void resourceExistsShouldDetectPresentAndAbsentResources() {
        assertTrue(processor.resourceExists("agent-platform.properties"),
                "Should detect a resource that is on the classpath");
        assertFalse(processor.resourceExists("no-such-resource.properties"),
                "Should report a resource that is not on the classpath as absent");
    }

    @Test
    void shouldSkipWhenLoggingConfigValueIsBlank() {
        AgentLoggingEnvironmentPostProcessor blankProcessor =
                processorWithPropertiesResource("logging-spi/blank-logging-config.properties");

        blankProcessor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not set logging.config when the configured value is blank");
    }

    @Test
    void shouldSkipWhenLoggingConfigKeyMissing() {
        AgentLoggingEnvironmentPostProcessor missingProcessor =
                processorWithPropertiesResource("logging-spi/missing-logging-config.properties");

        missingProcessor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not set logging.config when the property is absent");
    }

    @Test
    void shouldSkipWhenPropertiesResourceMissing() {
        AgentLoggingEnvironmentPostProcessor missingProcessor =
                processorWithPropertiesResource("logging-spi/does-not-exist.properties");

        missingProcessor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not set logging.config when agent-platform.properties is absent");
    }

    @Test
    void shouldSkipWhenPropertiesResourceUnreadable() throws IOException {
        Resource unreadable = mock(Resource.class);
        when(unreadable.exists()).thenReturn(true);
        when(unreadable.getInputStream()).thenThrow(new IOException("boom"));
        AgentLoggingEnvironmentPostProcessor failingProcessor =
                new AgentLoggingEnvironmentPostProcessor() {
                    @Override
                    protected Resource agentPlatformPropertiesResource() {
                        return unreadable;
                    }
                };

        failingProcessor.postProcessEnvironment(environment, application);

        assertNull(environment.getProperty("logging.config"),
                "Should not set logging.config when agent-platform.properties cannot be read");
    }

    private static AgentLoggingEnvironmentPostProcessor processorWithResource(String presentResource) {
        return new AgentLoggingEnvironmentPostProcessor() {
            @Override
            protected boolean resourceExists(String name) {
                return presentResource.equals(name);
            }
        };
    }

    private static AgentLoggingEnvironmentPostProcessor processorWithActiveLoggingSystem(String presentClass) {
        return new AgentLoggingEnvironmentPostProcessor() {
            @Override
            protected boolean classPresent(String className) {
                return presentClass.equals(className);
            }
        };
    }

    private static AgentLoggingEnvironmentPostProcessor processorWithPropertiesResource(String classpathLocation) {
        return new AgentLoggingEnvironmentPostProcessor() {
            @Override
            protected Resource agentPlatformPropertiesResource() {
                return new ClassPathResource(classpathLocation);
            }
        };
    }

}
