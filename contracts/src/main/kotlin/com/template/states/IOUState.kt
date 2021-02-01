package com.template.states

import com.template.contracts.IOUContract
import net.corda.core.contracts.Amount
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import java.util.*

@BelongsToContract(IOUContract::class)
data class IOUState(
    val amount: Amount<Currency>,
    val PesoNode: Party,
    val USDNode: Party,
    override val linearId: UniqueIdentifier = UniqueIdentifier(),
    override val participants: List<Party> = listOf(PesoNode, USDNode)
) : LinearState