package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Panel
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The window's Escape rule, as a truth table.
 *
 * [escapeClosesPlayer] is the whole of the handler `Window(onPreviewKeyEvent = …)` installs
 * (Main.kt). It was also run for real — a posted `WM_KEYDOWN`/`WM_KEYUP` pair for `VK_ESCAPE`
 * reaches the window handle without the foreground activation this machine will not give a
 * scripted pass, and it closed the overlay over a playing track. What that run cannot do is
 * cover the rows a hand will not think to try: the press of a held key, the typed form of the
 * same key, Escape with the player shut. So the table below pins all eight, and stays the
 * regression net for a live pass that only ever presses the one that works.
 *
 * The rule is a pure function of three values and reads no state of its own, which is what
 * makes this a truth table rather than a mock: the four rows with a non-Escape key or
 * `playerIsOpen = false` are the four ways Escape keeps belonging to the search field.
 */
class EscapeRuleTest {

    // ---- the platform's own conversion, restated -----------------------------------------
    //
    // Compose Multiplatform folds a `java.awt.event.KeyEvent` into the two values the handler
    // sees before the handler ever runs, and it is not reachable from here: `toComposeEvent`
    // is internal to the library. These two helpers are that fold, read off the 1.12.1 class
    // file, so the row that matters — a released Escape — can be written as the OS writes it.
    // They are a restatement, not an assertion about the library: what they prove is that the
    // constant this rule compares against is the constant the platform produces, and that a
    // press, a release and a typed event are three different inputs.

    /** `Key(nativeKeyCode, nativeKeyLocation)`, with AWT's unknown location folded to standard. */
    private fun composeKey(keyCode: Int, keyLocation: Int): Key =
        Key(keyCode, if (keyLocation == AwtKeyEvent.KEY_LOCATION_UNKNOWN) AwtKeyEvent.KEY_LOCATION_STANDARD else keyLocation)

    /** KEY_PRESSED and KEY_RELEASED are the only two types; every other id — KEY_TYPED among them — is unknown. */
    private fun composeType(id: Int): KeyEventType = when (id) {
        AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
        AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
        else -> KeyEventType.Unknown
    }

    /**
     * A real AWT event, the way the window's key listener receives one.
     *
     * Presses and releases carry `CHAR_UNDEFINED`, which is what the seven-argument
     * constructor demands of them; a typed event demands the opposite — a real character and
     * no key code at all — so the two cases differ in the one argument the rule never reads.
     */
    private fun awt(
        id: Int,
        keyCode: Int,
        keyLocation: Int,
        keyChar: Char = AwtKeyEvent.CHAR_UNDEFINED,
    ): AwtKeyEvent =
        AwtKeyEvent(
            Panel(),
            id,
            System.currentTimeMillis(),
            0,
            keyCode,
            keyChar,
            keyLocation,
        )

    /** Both halves of the fold at once, for a row written as the OS delivers it. */
    private fun fromAwt(
        id: Int,
        keyCode: Int,
        keyLocation: Int = AwtKeyEvent.KEY_LOCATION_UNKNOWN,
        keyChar: Char = AwtKeyEvent.CHAR_UNDEFINED,
    ): Pair<Key, KeyEventType> {
        val event = awt(id, keyCode, keyLocation, keyChar)
        return composeKey(event.keyCode, event.keyLocation) to composeType(event.id)
    }

    // ---- the table -----------------------------------------------------------------------

    @Test
    fun `Escape released with the player up is the one event that closes it`() {
        assertTrue(escapeClosesPlayer(Key.Escape, KeyEventType.KeyUp, playerIsOpen = true))
    }

    @Test
    fun `Escape released with the player shut goes on to the focused component`() {
        // The player is not up, so the handler answers `false` and the key falls through to
        // whatever has focus — on this build that is the search field's existing clear-on-Escape.
        assertFalse(escapeClosesPlayer(Key.Escape, KeyEventType.KeyUp, playerIsOpen = false))
    }

    @Test
    fun `Escape pressed is not consumed, so a held key closes the player once`() {
        // A held key repeats its press and produces a single release. Swallowing the press
        // would have the handler answer a key that is still going down.
        assertFalse(escapeClosesPlayer(Key.Escape, KeyEventType.KeyDown, playerIsOpen = true))
    }

    @Test
    fun `an event of unknown type is not consumed`() {
        // The type condition on its own row: everything that is neither a press nor a release
        // — the typed form of the key above all — stays where it was.
        assertFalse(escapeClosesPlayer(Key.Escape, KeyEventType.Unknown, playerIsOpen = true))
    }

    @Test
    fun `no other key closes the player`() {
        assertFalse(escapeClosesPlayer(composeKey(AwtKeyEvent.VK_ENTER, AwtKeyEvent.KEY_LOCATION_STANDARD), KeyEventType.KeyUp, playerIsOpen = true))
        assertFalse(escapeClosesPlayer(composeKey(AwtKeyEvent.VK_SPACE, AwtKeyEvent.KEY_LOCATION_STANDARD), KeyEventType.KeyUp, playerIsOpen = true))
    }

    @Test
    fun `the Escape the platform builds for a released key is the Escape the rule compares against`() {
        // The one link the rest of the table takes on trust: `Key.Escape` has to be the value
        // the window actually hands over, or the handler is correct and deaf. AWT reports an
        // ordinary Escape with an unknown location, which the platform folds to standard —
        // both spellings are checked, because folding is exactly where a mismatch would hide.
        assertEquals(Key.Escape, composeKey(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.KEY_LOCATION_UNKNOWN))
        assertEquals(Key.Escape, composeKey(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.KEY_LOCATION_STANDARD))
        // And the fold is not a no-op: `Key` packs the code *and* the location, so a key that
        // arrived at some other location would be a different key. Nothing on this platform
        // delivers a left-side Escape, which is why the rule can compare the packed value.
        assertNotEquals(Key.Escape, composeKey(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.KEY_LOCATION_LEFT))
    }

    @Test
    fun `a real Escape press-release pair closes the player once`() {
        val (pressedKey, pressedType) = fromAwt(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ESCAPE)
        val (releasedKey, releasedType) = fromAwt(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ESCAPE)
        // AWT insists a typed event carry the character and no key code, so the typed form of
        // Escape fails on the code as well as on the type; the row above is the one that
        // isolates the type.
        val (typedKey, typedType) = fromAwt(
            AwtKeyEvent.KEY_TYPED,
            AwtKeyEvent.VK_UNDEFINED,
            keyChar = ESCAPE_CHAR,
        )

        assertFalse(escapeClosesPlayer(pressedKey, pressedType, playerIsOpen = true))
        assertTrue(escapeClosesPlayer(releasedKey, releasedType, playerIsOpen = true))
        assertFalse(escapeClosesPlayer(typedKey, typedType, playerIsOpen = true))
    }

    @Test
    fun `a real Escape release with the player shut is left alone`() {
        val (key, type) = fromAwt(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ESCAPE)
        assertFalse(escapeClosesPlayer(key, type, playerIsOpen = false))
    }
}

/** The character AWT puts on a typed Escape — the only thing such an event can carry. */
private val ESCAPE_CHAR = 0x1B.toChar()
