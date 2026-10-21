package com.example.mcpoauthdemo.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CryptoSupportTest {

    @Test
    void producesTheRfc7636AppendixBChallenge() {
        CryptoSupport crypto = new CryptoSupport();
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

        assertThat(crypto.isValidPkceValue(verifier)).isTrue();
        assertThat(crypto.s256(verifier)).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
