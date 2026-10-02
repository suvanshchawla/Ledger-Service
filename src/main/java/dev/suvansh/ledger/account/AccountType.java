package dev.suvansh.ledger.account;

public enum AccountType {
    /** Belongs to a customer; the balance may never go below zero. */
    CUSTOMER,
    /** Owned by the platform (e.g. the treasury); may go negative. Never created through the API. */
    SYSTEM
}
