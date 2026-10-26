package com.leadsystems.minecraft.ascension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginBootTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnablesUnderMockBukkit() {
        AscensionPlugin plugin = MockBukkit.load(AscensionPlugin.class);
        assertTrue(plugin.isEnabled());
        server.addPlayer("Alex");
    }
}
