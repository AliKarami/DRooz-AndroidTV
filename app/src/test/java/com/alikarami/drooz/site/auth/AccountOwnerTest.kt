package com.alikarami.drooz.site.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountOwnerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun firstClaimAdoptsWithoutReportingAChange() {
        assertFalse(AccountOwner(tmp.root.resolve("account.id")).claim("alpha"))
    }

    @Test fun sameAccountIsNotAChangeIgnoringCaseAndSpaces() {
        val file = tmp.root.resolve("account.id")
        AccountOwner(file).claim("Alpha")
        assertFalse(AccountOwner(file).claim(" alpha "))
    }

    @Test fun anotherAccountIsAChangeAndBecomesTheOwner() {
        val file = tmp.root.resolve("account.id")
        val owner = AccountOwner(file)
        owner.claim("alpha")
        assertTrue(owner.claim("beta"))
        assertFalse(owner.claim("beta"))
        assertTrue(AccountOwner(file).claim("alpha"))
    }

    // The marker is a hash: the username itself is not written to disk a second time.
    @Test fun fileDoesNotContainTheUsername() {
        val file = tmp.root.resolve("account.id")
        AccountOwner(file).claim("someaccount")
        assertFalse(file.readText().contains("someaccount"))
    }
}
