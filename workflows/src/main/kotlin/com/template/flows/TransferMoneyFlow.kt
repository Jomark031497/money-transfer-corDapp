package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.states.IOUState
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.lang.IllegalArgumentException
import java.util.*

object TransferMoneyFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val amount: Amount<Currency>,
        private val counterParty: Party
    ) : FlowLogic<SignedTransaction>() {


        private fun states(): IOUState {

            when (ourIdentity.name.organisation) {

                "USD" -> {
                    if (amount.toString().contains("USD")) {

                        IOUState(
                            usdBalance = listOf(
                                Amount.parseCurrency("100000 USD") - amount,
                                Amount.parseCurrency("0 PHP")
                            ),
                            phpBalance = listOf(
                                Amount.parseCurrency("0 USD") + amount,
                                Amount.parseCurrency("1000000 PHP")
                            ),
                            linearId = UniqueIdentifier(),
                            participants = listOf(ourIdentity, counterParty)
                        )
                    } else
                        IOUState(
                            usdBalance = listOf(
                                Amount.parseCurrency("100000 USD"),
                                Amount.parseCurrency("0 PHP") - amount
                            ),
                            phpBalance = listOf(
                                Amount.parseCurrency("0 USD"),
                                Amount.parseCurrency("1000000 PHP") + amount
                            ),
                            linearId = UniqueIdentifier(),
                            participants = listOf(ourIdentity, counterParty)
                        )
                }

            }

            // if nothing, it just returns the current state
            return IOUState(
                usdBalance = listOf(
                    Amount.parseCurrency("100000 USD"),
                    Amount.parseCurrency("0 PHP")
                ),
                phpBalance = listOf(
                    Amount.parseCurrency("0 USD"),
                    Amount.parseCurrency("1000000 PHP")
                ),
                linearId = UniqueIdentifier(),
                participants = listOf(ourIdentity, counterParty)
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val transaction: TransactionBuilder = transaction(states())
            val signedTransaction: SignedTransaction = verifyAndSign(transaction)
            val sessions: List<FlowSession> =
                (states().participants - ourIdentity).map { initiateFlow(it) }.toSet().toList()
            val transactionSignedByAllParties: SignedTransaction = collectSignature(signedTransaction, sessions)
            return recordTransaction(transactionSignedByAllParties, sessions)
        }

        private fun transaction(state: IOUState): TransactionBuilder {
            val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()
            val issueCommand = Command(IOUContract.Commands.Issue(), ourIdentity.owningKey)
            val builder = TransactionBuilder(notary = notary)
            builder.addOutputState(state, IOUContract.IOU_CONTRACT_ID)
            builder.addCommand(issueCommand)
            return builder
        }

        private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
            transaction.verify(serviceHub)
            return serviceHub.signInitialTransaction(transaction)
        }

        @Suspendable
        fun collectSignature(
            transaction: SignedTransaction,
            sessions: List<FlowSession>
        ): SignedTransaction = subFlow(CollectSignaturesFlow(transaction, sessions))

        @Suspendable
        fun recordTransaction(transaction: SignedTransaction, sessions: List<FlowSession>): SignedTransaction =
            subFlow(FinalityFlow(transaction, sessions))
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction." using (output is IOUState)
                    val iou = output as IOUState
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}
