package com.aicode.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SSH 连接异常 → 友好提示文案的翻译逻辑（[friendlySshError]）。
 */
class FriendlySshErrorTest {

    @Test
    fun connection_refused() {
        assertEquals(
            "Cannot connect to the SSH server (connection refused); please make sure the remote server is running and the port is correct",
            friendlySshError(RuntimeException("ECONNREFUSED (Connection refused)"))
        )
        assertEquals(
            "Cannot connect to the SSH server (connection refused); please make sure the remote server is running and the port is correct",
            friendlySshError(RuntimeException("Connection refused: 10.0.0.2:22"))
        )
    }

    @Test
    fun unknown_host() {
        assertEquals(
            "Cannot connect to the SSH server (unknown host); please check the host address",
            friendlySshError(RuntimeException("UnknownHostException: foo.example.com"))
        )
        assertEquals(
            "Cannot connect to the SSH server (unknown host); please check the host address",
            friendlySshError(RuntimeException("unknown host foo"))
        )
    }

    @Test
    fun auth_failed() {
        assertEquals(
            "SSH authentication failed; please check the username and password",
            friendlySshError(RuntimeException("Auth fail"))
        )
        assertEquals(
            "SSH authentication failed; please check the username and password",
            friendlySshError(RuntimeException("authentication failed"))
        )
    }

    @Test
    fun network_unreachable() {
        assertEquals(
            "Network is unreachable; please check the network connection",
            friendlySshError(RuntimeException("Network is unreachable"))
        )
    }

    @Test
    fun timeout() {
        assertEquals(
            "Connecting to the SSH server timed out; please check the network or server status",
            friendlySshError(RuntimeException("SocketTimeoutException: connect timed out"))
        )
        assertEquals(
            "Connecting to the SSH server timed out; please check the network or server status",
            friendlySshError(RuntimeException("timed out"))
        )
    }

    @Test
    fun not_connected() {
        assertEquals(
            "SSH is not connected; please wait for the connection to recover or check the configuration in Settings",
            friendlySshError(RuntimeException("SSH not connected"))
        )
    }

    @Test
    fun unknown_error_falls_back() {
        assertEquals("SSH connection failed: boom", friendlySshError(RuntimeException("boom")))
        assertEquals("SSH connection failed: unknown error", friendlySshError(RuntimeException()))
    }
}
