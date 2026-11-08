package com.acme;

import com.acme.events.Order;
import com.acme.events.common.Money;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Money total = new Money();
        total.setCents(1250);
        total.setCurrency("USD");
        Order order = new Order();
        order.setId(7);
        order.setTotal(total);
        System.out.println(order.getId() + " costs " + order.getTotal().getCents());
    }
}
