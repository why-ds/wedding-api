package com.wedding.mail;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Amazon SES (API v2). Credentials come from the default AWS chain, so on EC2 the instance role
 * supplies them and no access key is stored in configuration. The role needs only ses:SendEmail.
 */
public class SesMailSender implements MailSender, AutoCloseable {
    private final SesV2Client client;
    private final String from;
    public SesMailSender(String region, String from) {
        this(SesV2Client.builder().region(Region.of(region)).build(), from);
    }
    SesMailSender(SesV2Client client, String from) { this.client=client; this.from=from; }
    @Override public void send(String to, String subject, String text) {
        client.sendEmail(request->request
            .fromEmailAddress(from)
            .destination(d->d.toAddresses(to))
            .content(c->c.simple(m->m
                .subject(s->s.data(subject).charset("UTF-8"))
                .body(b->b.text(t->t.data(text).charset("UTF-8"))))));
    }
    @Override public void close() { client.close(); }
}
