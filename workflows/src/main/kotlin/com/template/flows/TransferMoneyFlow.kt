package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.states.IOUState
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.lang.IllegalArgumentException
import java.util.*

object TransferMoneyFlow {

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val amount: Amount<Currency>,
        private val counterParty: Party,
        private val linearId: UniqueIdentifier
    ) : FlowLogic<SignedTransaction>() {


        private fun states(data: StateAndRef<IOUState>): IOUState {


            val data = data.state.data
            // Current State
            var myState: IOUState = IOUState(
                usdBalance = data.usdBalance,
                phpBalance = data.phpBalance,
                linearId = linearId,
                participants = listOf(ourIdentity, counterParty)
            );

            // If you are USD, you will transfer X amount of USD to Peso
            if (ourIdentity.name.organisation == "USD") {

                (if (amount.toString().contains("USD")) {
                    myState = IOUState(
                        usdBalance = listOf(
                            data.usdBalance[0] - amount,
                            data.usdBalance[1]
                        ),
                        phpBalance = listOf(
                            data.phpBalance[0] + amount,
                            data.phpBalance[1]
                        ),
                        linearId = linearId,
                        participants = listOf(ourIdentity, counterParty)
                    )

                } else if (amount.toString().contains("PHP")) {
                    myState = IOUState(
                        usdBalance = listOf(
                            data.usdBalance[0],
                            data.usdBalance[1] - amount
                        ),
                        phpBalance = listOf(
                            data.phpBalance[0],
                            data.phpBalance[1] + amount
                        ),
                        linearId = linearId,
                        participants = listOf(ourIdentity, counterParty)
                    )
                })

                //else if you are Peso, you will transfer X amount of PHP to USD
            } else if (ourIdentity.name.organisation == "Peso") {
                (if (amount.toString().contains("USD")) {

                    myState = IOUState(
                        usdBalance = listOf(
                            data.usdBalance[0] + amount,
                            data.usdBalance[1]
                        ),
                        phpBalance = listOf(
                            data.phpBalance[0] - amount,
                            data.phpBalance[1]
                        ),
                        linearId = linearId,
                        participants = listOf(ourIdentity, counterParty)
                    )
                } else if (amount.toString().contains("PHP")) {
                    myState = IOUState(
                        usdBalance = listOf(
                            data.usdBalance[0],
                            data.usdBalance[1] + amount
                        ),
                        phpBalance = listOf(
                            data.phpBalance[0],
                            data.phpBalance[1] - amount
                        ),
                        linearId = linearId,
                        participants = listOf(ourIdentity, counterParty)
                    )
                }
                        )
            }

            return myState
        }

        @Suspendable
        override fun call(): SignedTransaction {

            val vaultData = getVaultData(linearId)
            val transaction: TransactionBuilder = transaction(states(vaultData), vaultData)
            val signedTransaction: SignedTransaction = verifyAndSign(transaction)
            val sessions: List<FlowSession> =
                (states(vaultData).participants - ourIdentity).map { initiateFlow(it) }.toSet().toList()
            val transactionSignedByAllParties: SignedTransaction = collectSignature(signedTransaction, sessions)
            return recordTransaction(transactionSignedByAllParties, sessions)
        }

        private fun getVaultData(linearId: UniqueIdentifier): StateAndRef<IOUState> {
            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
            return serviceHub.vaultService.queryBy<IOUState>(queryCriteria).states.single()
        }

        private fun transaction(state: IOUState, data: StateAndRef<IOUState>): TransactionBuilder {
            val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()
            val issueCommand = Command(IOUContract.Commands.Issue(), states(data).participants.map { it.owningKey })
            val builder = TransactionBuilder(notary = notary)
            builder
                .addInputState(data)
                .addOutputState(state, IOUContract.IOU_CONTRACT_ID)
                .addCommand(issueCommand)
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
