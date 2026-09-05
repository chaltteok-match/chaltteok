package com.chaltteok.bond;

enum BondForm {
    CONVERTIBLE("전환사채권발행결정"),
    EXCHANGEABLE("교환사채권발행결정");

    private final String docSubtype;

    BondForm(String docSubtype) {
        this.docSubtype = docSubtype;
    }

    String docSubtype() {
        return docSubtype;
    }
}
