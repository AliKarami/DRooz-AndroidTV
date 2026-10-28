package com.alikarami.drooz.testutil

/** Loads a sanitized site page from src/test/resources/fixtures. */
fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.classLoader!!.getResource("fixtures/$name")) { "missing fixture $name" }
        .readText()
