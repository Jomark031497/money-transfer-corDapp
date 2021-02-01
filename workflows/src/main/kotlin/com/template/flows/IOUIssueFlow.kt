package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.states.IOUState
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.util.*

@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(
    val amount: Amount<Currency>,
    val counterParty: Party
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        //val transaction: TransactionBuilder = transaction()
        //val signedTransaction: SignedTransaction = verifyAndSign(transaction)
        //val sessions: List<FlowSession> = (IOUState.participants - ourIdentity).map { initiateFlow(it) }.toSet().toList()
        //val transactionSignedByAllParties: SignedTransaction = collectSignature(signedTransaction, sessions)
        //return recordTransaction(transactionSignedByAllParties, sessions)
        val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()

        // Generate an unsigned transaction.
        val iouState = IOUState(amount, serviceHub.myInfo.legalIdentities.first(), counterParty)
        val issueCommand: Command<IOUContract.Commands.Issue> =
            Command(IOUContract.Commands.Issue(), iouState.participants.map { it.owningKey })
        val txBuilder = TransactionBuilder(notary = notary)
        txBuilder
            .addOutputState(iouState, IOUContract.IOU_CONTRACT_ID)
            .addCommand(issueCommand)

        // Verify that the transaction is valid.
        txBuilder.verify(serviceHub)

        // Sign the transaction.
        val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

        // Send the state to the counterparty, and receive it back with their signature.
        val otherPartySession = initiateFlow(counterParty)
        val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession)))

        // Notarise and record the transaction in both parties' vaults.
        return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession)))
    }


}

@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        val signedTransaction = subFlow(signTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = signedTransaction.id))
    }
}