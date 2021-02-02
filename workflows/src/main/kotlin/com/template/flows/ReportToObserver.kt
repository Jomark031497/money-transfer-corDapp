package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.unwrap

@InitiatingFlow
class ReportToObserver(val signedTransaction: SignedTransaction, val observer: Party): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val session = initiateFlow(observer)
        session.send(signedTransaction)
    }
}

@InitiatedBy(ReportToObserver::class)
class ReportManuallyResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransaction = counterpartySession.receive<SignedTransaction>().unwrap { it }
        // The national regulator records all of the transaction's states using
        // `recordTransactions` with the `ALL_VISIBLE` flag.
        serviceHub.recordTransactions(StatesToRecord.ALL_VISIBLE, listOf(signedTransaction))
    }
}