package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object ProposalFlowPair {
    enum class Role { Buyer, Seller }

    @InitiatingFlow
    @StartableByRPC
    class ProposeTrade(val role: Role, val amount: Int, val counterparty: Party) : FlowLogic<Unit>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            // Creating the output.
            val (buyer, seller) = when (role) {
                Role.Buyer -> ourIdentity to counterparty
                Role.Seller -> counterparty to ourIdentity
            }
            val output = ProposalState(amount, buyer, seller, ourIdentity, counterparty)

            // Creating the command.
            val commandType = ProposalAndTradeContract.Commands.Propose()
            val requiredSigners = listOf(ourIdentity.owningKey, counterparty.owningKey)
            val command = Command(commandType, requiredSigners)

            // Building the transaction.
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addOutputState(output, ProposalAndTradeContract.ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            subFlow(FinalityFlow(fullyStx))
        }
    }

    @InitiatedBy(ProposeTrade::class)
    class ProposeTradeResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    // No checking to be done.
                }
            })
        }
    }
}

object AcceptanceFlowPair {
    @InitiatingFlow
    @StartableByRPC
    class AcceptProposal(val proposalId: UniqueIdentifier) : FlowLogic<Unit>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            // Retrieving the input from the vault.
            val inputCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(proposalId))
            val inputStateAndRef = serviceHub.vaultService.queryBy<ProposalState>(inputCriteria).states.single()
            val input = inputStateAndRef.state.data

            // Creating the output.
            val output = TradeState(input.amount, input.buyer, input.seller, input.linearId)

            // Creating the command.
            val requiredSigners = listOf(input.proposer.owningKey, input.proposee.owningKey)
            val command = Command(ProposalAndTradeContract.Commands.Accept(), requiredSigners)

            // Building the transaction.
            val notary = inputStateAndRef.state.notary
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addInputState(inputStateAndRef)
            txBuilder.addOutputState(output, ProposalAndTradeContract.ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val counterparty = serviceHub.identityService.requireWellKnownPartyFromAnonymous(input.proposer)
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            subFlow(FinalityFlow(fullyStx))
        }
    }

    @InitiatedBy(AcceptProposal::class)
    class AcceptProposalResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    val ledgerTx = stx.toLedgerTransaction(serviceHub, false)
                    val proposee = ledgerTx.inputsOfType<ProposalState>().single().proposee
                    if (proposee != counterpartySession.counterparty) {
                        throw FlowException("Only the proposee can accept a proposal.")
                    }
                }
            })
        }
    }
}

object ModificationFlowPair {
    @InitiatingFlow
    @StartableByRPC
    class ModifyProposal(val proposalId: UniqueIdentifier, val newAmount: Int) : FlowLogic<Unit>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            // Retrieving the input from the vault.
            val inputCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(proposalId))
            val inputStateAndRef = serviceHub.vaultService.queryBy<ProposalState>(inputCriteria).states.single()
            val input = inputStateAndRef.state.data

            // Creating the output.
            val counterparty = serviceHub.identityService.requireWellKnownPartyFromAnonymous(input.proposer)
            val output = input.copy(amount = newAmount, proposer = ourIdentity, proposee = counterparty)

            // Creating the command.
            val requiredSigners = listOf(input.proposer.owningKey, input.proposee.owningKey)
            val command = Command(ProposalAndTradeContract.Commands.Modify(), requiredSigners)

            // Building the transaction.
            val notary = inputStateAndRef.state.notary
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addInputState(inputStateAndRef)
            txBuilder.addOutputState(output, ProposalAndTradeContract.ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            subFlow(FinalityFlow(fullyStx))
        }
    }

    @InitiatedBy(ModifyProposal::class)
    class ModifyProposalResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    val ledgerTx = stx.toLedgerTransaction(serviceHub, false)
                    val proposee = ledgerTx.inputsOfType<ProposalState>().single().proposee
                    if (proposee != counterpartySession.counterparty) {
                        throw FlowException("Only the proposee can modify a proposal.")
                    }
                }
            })
        }
    }
}