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
class IssueCashFlow(private val phpBalancePeso: Amount<Currency>,
                    private val usdBalancePeso: Amount<Currency>,
                    private val phpBalanceUSD: Amount<Currency>,
                    private val usdBalanceUSD: Amount<Currency>,
                    private val counterParty: Party,
                    private val observer: Party) : FlowLogic<Unit>() {

    private fun states(): MoneyTransferState {
        return MoneyTransferState(
            linearId = UniqueIdentifier(),
            participants = listOf(ourIdentity, counterParty),
            phpBalance = listOf(usdBalancePeso, phpBalancePeso),
            usdBalance = listOf(usdBalanceUSD, phpBalanceUSD)
        )
    }

    @Suspendable
    override fun call(): Unit {
        val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()
        val issueCommand = Command(IOUContract.Commands.Issue(), states().participants.map { it.owningKey })
        val txBuilder = TransactionBuilder(notary = notary)
        txBuilder
            .addOutputState(states(), IOUContract.IOU_CONTRACT_ID)
            .addCommand(issueCommand)
        txBuilder.verify(serviceHub)
        val partSignedTx = serviceHub.signInitialTransaction(txBuilder)
        val sessions = initiateFlow(counterParty)
        val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(sessions)))
        subFlow(FinalityFlow(fullySignedTx, sessions))

        subFlow(ReportToObserver(fullySignedTx, observer))
    }
}

@InitiatedBy(IssueCashFlow::class)
class IssueCashResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call(): Unit {
        val signTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is MoneyTransferState)
            }
        }

        val txId = subFlow(signTransactionFlow).id
        subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txId, statesToRecord = StatesToRecord.ALL_VISIBLE)
        )
    }
}
