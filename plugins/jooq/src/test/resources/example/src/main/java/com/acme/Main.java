package com.acme;

import static com.acme.jooq.Tables.CUSTOMERS;
import static com.acme.jooq.Tables.ORDERS;

import org.jooq.impl.DSL;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.out.println(DSL.select(CUSTOMERS.EMAIL, ORDERS.TOTAL)
                .from(ORDERS)
                .join(CUSTOMERS)
                .on(ORDERS.CUSTOMER_ID.eq(CUSTOMERS.ID))
                .getSQL());
    }
}
