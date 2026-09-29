package com.wedding.mail;

/** Plain-text transactional mail. Implementations must not log message bodies outside local demo use. */
public interface MailSender {
    void send(String to, String subject, String text);
}
