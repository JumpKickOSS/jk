create table orders (
  id bigint primary key,
  customer_id bigint not null references customers(id),
  total decimal(10, 2) not null
);
