package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.states.IOUState
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

@InitiatingFlow
@StartableByRPC
class IssueCashFlow(
    val phpBalancePeso: Amount<Currency>,
    val usdBalancePeso: Amount<Currency>,
    val phpBalanceUSD: Amount<Currency>,
    val usdBalanceUSD: Amount<Currency>,
    val counterParty: Party
) : FlowLogic<SignedTransaction>() {

    private fun iouStates(): IOUState {
        return IOUState(
            linearId = UniqueIdentifier(),
            participants = listOf(ourIdentity, counterParty),
            phpBalance = listOf(usdBalancePeso, phpBalancePeso),
            usdBalance = listOf(usdBalanceUSD, phpBalanceUSD)
        )
    }

    @Suspendable
    override fun call(): SignedTransaction {

        val transaction: TransactionBuilder = transaction(iouStates())
        val signedTransaction: SignedTransaction = verifyAndSign(transaction)
        val sessions: List<FlowSession> =
            (iouStates().participants - ourIdentity).map { initiateFlow(it) }.toSet().toList()
        val transactionSignedByAllParties: SignedTransaction = collectSignature(signedTransaction, sessions)
        return recordTransaction(transactionSignedByAllParties, sessions)
    }

    private fun transaction(state: IOUState): TransactionBuilder {
        val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()
        val issueCommand = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })
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

@InitiatedBy(IssueCashFlow::class)
class IssueCashResponder(val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
            }
        }
        val signedTransaction = subFlow(signTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = signedTransaction.id))
    }
}