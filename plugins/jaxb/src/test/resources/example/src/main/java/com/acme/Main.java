package com.acme;

import com.acme.order.Order;
import jakarta.xml.bind.JAXBContext;
import java.io.StringWriter;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Order order = new Order();
        order.setId("A-1");
        order.setQuantity(3);
        StringWriter out = new StringWriter();
        JAXBContext.newInstance(Order.class).createMarshaller().marshal(order, out);
        System.out.println(out);
    }
}
