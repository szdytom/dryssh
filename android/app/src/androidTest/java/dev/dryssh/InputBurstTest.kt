package dev.dryssh

import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Test

class InputBurstTest {
	@Test fun fastPhysicalInputStaysOrderedWithoutClosingConnection() {
		SessionFixture().use { fixture ->
			fixture.connect()
			val before = fixture.shellPid("burst-before")
			val token = System.nanoTime().toString()
			val command = "value='${"a".repeat(
				600
			)}'; test \"\${#value}\" -eq 600 && printf '%s%s\\n' 'https://example.' 'org/burst-$token'"
			fixture.instrumentation.runOnMainSync {
				command.forEach { fixture.service.terminal.dispatchCharacter(0, it.code) }
				fixture.service.terminal.dispatchKey(0, VTermKey.ENTER)
			}
			fixture.awaitCondition {
				fixture.service.terminal.getUrls().any {
					it.url ==
						"https://example.org/burst-$token"
				}
			}
			assertEquals(before, fixture.shellPid("burst-after"))
		}
	}
}
