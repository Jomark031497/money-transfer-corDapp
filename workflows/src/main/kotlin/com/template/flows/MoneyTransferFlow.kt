package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.states.MoneyTransferState
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

object MoneyTransferFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val amount: Amount<Currency>,
                    private val counterParty: Party,
                    private val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

        private fun states(data: StateAndRef<MoneyTransferState>): MoneyTransferState {
            // De-structure data from vault
            val data = data.state.data
            // Current State
            var myState = MoneyTransferState(
                usdBalance = data.usdBalance,
                phpBalance = data.phpBalance,
                linearId = linearId,
                participants = listOf(ourIdentity, counterParty)
            )

            when (ourIdentity.name.organisation) {
                "USD" -> {
                    if (amount.toString().contains("USD")) {
                        myState = MoneyTransferState(
                            usdBalance = listOf(data.usdBalance[0] - amount, data.usdBalance[1]),
                            phpBalance = listOf(data.phpBalance[0] + amount, data.phpBalance[1]),
                            linearId = linearId,
                            participants = listOf(ourIdentity, counterParty)
                        )
                    }

                    else if (amount.toString().contains("PHP")) {
                        myState = MoneyTransferState(
                            usdBalance = listOf(data.usdBalance[0], data.usdBalance[1] - amount),
                            phpBalance = listOf(data.phpBalance[0], data.phpBalance[1] + amount),
                            linearId = linearId,
                            participants = listOf(ourIdentity, counterParty)
                        )
                    }
                }

                "Peso" -> {
                    if (amount.toString().contains("USD")) {
                        myState = MoneyTransferState(
                            usdBalance = listOf(data.usdBalance[0] + amount, data.usdBalance[1]),
                            phpBalance = listOf(data.phpBalance[0] - amount, data.phpBalance[1]),
                            linearId = linearId,
                            participants = listOf(ourIdentity, counterParty)
                        )
                    }

                    else if (amount.toString().contains("PHP")) {
                        myState = MoneyTransferState(
                            usdBalance = listOf(data.usdBalance[0], data.usdBalance[1] + amount),
                            phpBalance = listOf(data.phpBalance[0], data.phpBalance[1] - amount),
                            linearId = linearId,
                            participants = listOf(ourIdentity, counterParty)
                        )
                    }
                }
            }
            return myState
        }

        @Suspendable
        override fun call(): SignedTransaction {

            // Obtain a reference from a notary.
            val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()

            // Get current data from the vault
            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
            val currentData = serviceHub.vaultService.queryBy<MoneyTransferState>(queryCriteria).states.single()

            // Generate an unsigned transaction.
            val issueCommand = Command(IOUContract.Commands.Issue(), states(currentData).participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary = notary)
            txBuilder
                .addInputState(currentData)
                .addOutputState(states(currentData), IOUContract.IOU_CONTRACT_ID)
                .addCommand(issueCommand)

            // Verify transaction
            txBuilder.verify(serviceHub)

            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Send the state to the counterparty, and receive it back with their signature.
            val counterPartySession = initiateFlow(counterParty)

            // Collect signatures
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(counterPartySession)))

            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, counterPartySession))

        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction." using (output is MoneyTransferState)
                }
            }
            val txId = subFlow(signTransactionFlow).id
            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}
