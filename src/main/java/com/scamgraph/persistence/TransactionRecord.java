package com.scamgraph.persistence;

import com.scamgraph.model.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Database table "transactions". One row = one uploaded payment.
 * Spring Data JPA creates the table automatically from this class.
 */
@Entity
@Table(name = "transactions")
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rowId;

    @Column(nullable = false)
    private String txnId;

    @Column(nullable = false)
    private String fromAccount;

    private String fromName;

    @Column(nullable = false)
    private String toAccount;

    private String toName;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private LocalDateTime txnTime;   // ("timestamp" is a reserved word in SQL)

    private String channel;

    protected TransactionRecord() {
        // required by JPA
    }

    public static TransactionRecord from(Transaction t) {
        TransactionRecord r = new TransactionRecord();
        r.txnId = t.id();
        r.fromAccount = t.from();
        r.fromName = t.fromName();
        r.toAccount = t.to();
        r.toName = t.toName();
        r.amount = t.amount();
        r.txnTime = t.time();
        r.channel = t.channel();
        return r;
    }

    public Transaction toTransaction() {
        return new Transaction(txnId, fromAccount, fromName, toAccount, toName, amount, txnTime, channel);
    }

    public Long getRowId() { return rowId; }
    public String getTxnId() { return txnId; }
    public String getFromAccount() { return fromAccount; }
    public String getFromName() { return fromName; }
    public String getToAccount() { return toAccount; }
    public String getToName() { return toName; }
    public long getAmount() { return amount; }
    public LocalDateTime getTxnTime() { return txnTime; }
    public String getChannel() { return channel; }
}
