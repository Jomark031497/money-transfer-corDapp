package com.template.states

import com.template.contracts.IOUContract
import net.corda.core.contracts.Amount
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import java.util.*

@BelongsToContract(IOUContract::class)
data class MoneyTransferState(val usdBalance: List<Amount<Currency>>,
                              val phpBalance: List<Amount<Currency>>,
                              override val linearId: UniqueIdentifier,
                              override val participants: List<Party>) : LinearState
