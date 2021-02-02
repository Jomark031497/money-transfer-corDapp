package com.template.contracts

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

// ************
// * Contract *
// ************
class IOUContract : Contract {

    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "com.template.contracts.IOUContract"
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        when (command.value) {
            is Commands.Issue -> requireThat {

            }
            is Commands.Transfer -> requireThat {
                // more conditions
            }
            is Commands.Settle -> {
                // more conditions
            }
        }
    }
}