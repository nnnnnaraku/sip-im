package org.example;

/** Signalling state of one immutable call identity, independent of media implementation. */
public enum CallState {
    DIALING, RINGING, ACCEPTED, ESTABLISHED, ENDING, ENDED
}
