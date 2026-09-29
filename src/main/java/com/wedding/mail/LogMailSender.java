package com.wedding.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stand-in when no mail provider is configured.
 * Demo: prints the whole message (with its one-time link) so local sign-up can be completed.
 * Elsewhere: drops the message and logs only a masked recipient, because links grant account access.
 */
public class LogMailSender implements MailSender {
    private static final Logger log=LoggerFactory.getLogger(LogMailSender.class);
    private final boolean revealBody;
    public LogMailSender(boolean revealBody) { this.revealBody=revealBody; }
    @Override public void send(String to, String subject, String text) {
        if(revealBody) log.info("Demo mail (not delivered)\nTo: {}\nSubject: {}\n\n{}",to,subject,text);
        else log.warn("Mail provider not configured; message to {} was not delivered",mask(to));
    }
    static String mask(String email) {
        int at=email.indexOf('@');
        return at<=1?"***"+email.substring(Math.max(at,0)):email.charAt(0)+"***"+email.substring(at);
    }
}
