package org.example;

/** Group-only SIP MESSAGE transport. SIP acceptance is not an end-to-end delivery receipt. */
public interface GroupTransport {
    void send(String recipient, String envelope, Completion completion) throws Exception;

    interface Completion {
        void completed(boolean sipAccepted, String detail);
    }
}
