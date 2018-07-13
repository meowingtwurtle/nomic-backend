package model

import persistence.ObjectPersistence
import java.time.Instant

data class Proposal(val number: Int, val text: String, val parent: Int?, val type: Type, val state: State, val proposer: String?, val voteThreshold: VoteThreshold, val voteClosing: Instant?, val mutability: Boolean) {
    enum class Type(val externalName: String, val defaultVoteThreshold: VoteThreshold = VoteThreshold.majority(), val hasParent: Boolean = true, val requiredMutability: Boolean? = true, val newMutability: Boolean? = null, val hasText: Boolean = false) {
        ENACTMENT("enactment", hasParent = false, hasText = true),
        AMENDMENT("amendment", hasText = true),
        REPEAL("repeal"),
        MAKE_IMMUTABLE("make_immutable", requiredMutability = true, newMutability = false),
        MAKE_MUTABLE("make_mutable", requiredMutability = false, newMutability = true, defaultVoteThreshold = VoteThreshold.fullUnanimous());

        init {
            // A proposal either must have a parent or must have text, otherwise bad things could happen
            if (!hasParent && !hasText) throw AssertionError()
        }

        companion object {
            fun fromExternalName(externalName: String?): Type {
                externalName ?: throw IllegalArgumentException("null proposal type")

                val matching = values().filter { it.externalName == externalName }
                if (matching.isEmpty()) throw IllegalArgumentException("invalid proposal type \"$externalName\"")
                return matching[0]
            }
        }
    }

    enum class State(val externalName: String) {
        VOTING_OPEN("open"), ADOPTED("adopted"), REJECTED("rejected");

        companion object {
            fun fromExternalName(externalName: String?): State {
                externalName ?: throw IllegalArgumentException("null state name")

                val matching = values().filter { it.externalName == externalName }
                if (matching.isEmpty()) throw IllegalArgumentException("invalid state name \"$externalName\"")
                return matching[0]
            }
        }
    }

    enum class Vote(val externalName: String) {
        IN_FAVOR("aye"), OPPOSE("no"), ABSTAIN("abstain");


        companion object {
            fun fromExternalName(externalName: String?): Vote {
                externalName ?: throw IllegalArgumentException("null vote type")

                val matching = values().filter { it.externalName == externalName }
                if (matching.isEmpty()) throw IllegalArgumentException("invalid vote type \"$externalName\"")
                return matching[0]
            }
        }
    }

    interface VoteThreshold {
        fun passesPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean
        fun passesOnClosing(ayes: Int, noes: Int, abstentions: Int): Boolean
        fun failsPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean
        fun failsOnClosing(ayes: Int, noes: Int, abstentions: Int): Boolean = !passesOnClosing(ayes, noes, abstentions)
        fun specifier(): String

        companion object Implementations {
            fun unanimousConsent(): VoteThreshold {
                return object : VoteThreshold {
                    override fun passesPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean {
                        return noes == 0
                    }

                    override fun passesOnClosing(ayes: Int, noes: Int, abstentions: Int): Boolean {
                        return noes == 0 && abstentions == 0
                    }

                    override fun failsPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean {
                        return noes > 0
                    }

                    override fun specifier(): String = "unanimousConsent"
                }
            }

            fun fullUnanimous(): VoteThreshold {
                return object : VoteThreshold {
                    override fun passesPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean {
                        return noes == 0 && abstentions == 0
                    }

                    override fun passesOnClosing(ayes: Int, noes: Int, abstentions: Int): Boolean {
                        return noes == 0 && abstentions == 0
                    }

                    override fun failsPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean {
                        return noes > 0
                    }

                    override fun specifier(): String = "fullUnanimous"
                }
            }

            fun majority(): VoteThreshold = FractionalPassing(1, 2, false, "majority")

            fun twoThirds(): VoteThreshold = FractionalPassing(2, 3, true)

            fun parseBySpec(spec: String): VoteThreshold {
                return when(spec) {
                    "unanimousConsent" -> unanimousConsent()
                    "fullUnanimous" -> fullUnanimous()
                    "majority" -> majority()
                    else -> parseDefaultSpec(spec)
                }
            }

            private fun defaultSpec(numerator: Int, denominator: Int, allowEqual: Boolean): String {
                return "$numerator-$denominator-$allowEqual"
            }

            private fun parseDefaultSpec(spec: String): VoteThreshold {
                val parts = spec.split("-")
                return FractionalPassing(parts[0].toInt(), parts[1].toInt(), parts[2].toBoolean())
            }

            data class FractionalPassing(val numerator: Int, val denominator: Int, val allowEqual: Boolean, val name: String = defaultSpec(numerator, denominator, allowEqual)) : VoteThreshold {
                override fun passesPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean {
                    // ayes/total > num/den -> ayes * den > total * num
                    val total = ayes + noes + abstentions
                    return (ayes * denominator).possiblyStrictCompare(total * numerator)
                }

                override fun passesOnClosing(ayes: Int, noes: Int, abstentions: Int): Boolean {
                    val total = ayes + noes
                    return (ayes * denominator).possiblyStrictCompare(total * numerator)
                }

                override fun failsPreemptively(ayes: Int, noes: Int, abstentions: Int): Boolean {
                    val total = ayes + noes + abstentions
                    return !((((ayes + abstentions)) * denominator).possiblyStrictCompare(total * numerator))
                }

                override fun specifier(): String {
                    return name
                }

                /// Compares > if equal not allowed, >= otherwise
                private fun Int.possiblyStrictCompare(that: Int): Boolean {
                    return if (this@FractionalPassing.allowEqual) this >= that else this > that
                }
            }
        }

    }
}

internal fun Proposal.withNewState(newState: Proposal.State): Proposal {
    return Proposal(
            number,
            text,
            parent,
            type,
            newState,
            proposer,
            voteThreshold,
            voteClosing,
            mutability
    )
}

internal fun Proposal.withVoteThreshold(newVoteThreshold: Proposal.VoteThreshold): Proposal {
    return Proposal(
            number,
            text,
            parent,
            type,
            state,
            proposer,
            newVoteThreshold,
            voteClosing,
            mutability
    )
}

internal fun Proposal.withMutability(newMutability: Boolean): Proposal {
    return Proposal(
            number,
            text,
            parent,
            type,
            state,
            proposer,
            voteThreshold,
            voteClosing,
            newMutability
    )
}

internal fun Proposal.closeWithState(newState: Proposal.State) {
    var newProposal = withNewState(newState)

    if (type.newMutability != null) {
        newProposal = newProposal.withMutability(type.newMutability)
    }

    ObjectPersistence.writeProposal(newProposal)

    for (proposalNumber in getConflicts()) {
        ObjectPersistence.writeProposal(
                ObjectPersistence
                    .getProposal(proposalNumber)!!
                    .withNewState(Proposal.State.REJECTED))
    }
}

internal fun Proposal.getConflicts(): List<Int> {
    if (parent == null) return emptyList()

    return ObjectPersistence.getAllProposals().filter { it.parent == this.parent && it.state == Proposal.State.VOTING_OPEN }.map { it.number }
}

internal fun Proposal.hasConflicts(): Boolean {
    return getConflicts().isNotEmpty()
}