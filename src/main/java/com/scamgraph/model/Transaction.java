package com.scamgraph.model;

import java.time.LocalDateTime;

/**
 * One money transfer between two bank accounts.
 * This is the basic unit of data that ScamGraph works on.
 *
 * @param id       transaction id (e.g. TXN000123)
 * @param from     sender account number
 * @param fromName sender name (optional, may be empty)
 * @param to       receiver account number
 * @param toName   receiver name (optional, may be empty)
 * @param amount   amount in rupees
 * @param time     date and time of the transfer
 * @param channel  UPI / IMPS / NEFT (optional)
 */
public record Transaction(String id,
                          String from,
                          String fromName,
                          String to,
                          String toName,
                          long amount,
                          LocalDateTime time,
                          String channel) {
}
