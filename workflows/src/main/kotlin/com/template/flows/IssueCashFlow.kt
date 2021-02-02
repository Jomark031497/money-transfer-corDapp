package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.states.MoneyTransferState
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

@InitiatingFlow
@StartableByRPC
class IssueCashFlow(
    private val phpBalancePeso: Amount<Currency>,
    private val usdBalancePeso: Amount<Currency>,
    private val phpBalanceUSD: Amount<Currency>,
    private val usdBalanceUSD: Amount<Currency>,
    private val counterParty: Party,
    private val observer: Party
) : FlowLogic<SignedTransaction>() {

    private fun states(): MoneyTransferState {
        return MoneyTransferState(
            linearId = UniqueIdentifier(),
            participants = listOf(ourIdentity, counterParty),
            phpBalance = listOf(usdBalancePeso, phpBalancePeso),
            usdBalance = listOf(usdBalanceUSD, phpBalanceUSD)
        )
    }

    @Suspendable
    override fun call(): SignedTransaction {
        // Obtain a reference from a notary we wish to use.
        val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()

        // Generate an unsigned transaction.
        val issueCommand = Command(IOUContract.Commands.Issue(), states().participants.map { it.owningKey })
        val txBuilder = TransactionBuilder(notary = notary)
        txBuilder
            .addOutputState(states(), IOUContract.IOU_CONTRACT_ID)
            .addCommand(issueCommand)

        // Verify transaction
        txBuilder.verify(serviceHub)

        // Sign the transaction.
        val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

        // Send the state to the counterparty, and receive it back with their signature.
        val counterPartySession = initiateFlow(counterParty)

        val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(counterPartySession)))

        // Notarise and record the transaction in both parties' vaults.
        return subFlow(FinalityFlow(fullySignedTx, counterPartySession))
    }
}

@InitiatedBy(IssueCashFlow::class)
class IssueCashResponder(val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is MoneyTransferState)
            }
        }

        val txId = subFlow(signTransactionFlow).id
        return subFlow(
            ReceiveFinalityFlow(
                otherSideSession = flowSession,
                expectedTxId = txId,
                statesToRecord = StatesToRecord.ALL_VISIBLE
            )
        )
    }
}