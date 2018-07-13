package web.object_management

import model.Proposal
import model.hasConflicts
import model.withMutability
import model.withVoteThreshold
import org.json.JSONArray
import org.json.JSONObject
import persistence.ObjectPersistence
import persistence.UserPersistence
import web.*
import java.time.Instant
import java.time.temporal.ChronoUnit

object ProposalRequestHandler {

    fun getProposal(urlParameters: List<String>, body: JSONObject?): Response {
        val badRequest = badRequest(reason = "need exactly 1 url parameter of type int")

        if (urlParameters.size != 1 || urlParameters[0].toIntOrNull() == null) return badRequest

        val proposal = ObjectPersistence.getProposal(urlParameters[0].toInt()) ?: return notFound("invalid proposal number")
        return success(json = proposal.toJSON())
    }

    fun createProposal(urlParameters: List<String>, body: JSONObject?): Response {
        if (!body.isAuthorized()) return unauthorized()
        body ?: return badRequest(reason = "need body")

        val proposalNumber = ObjectPersistence.currentProposalNumber()

        var proposal: Proposal

        val admin = body.isAdmin()
        val bypassAllChecks = admin && body.optBoolean("bypass_all_checks")
        val bypassConflictCheck = admin && (bypassAllChecks || body.optBoolean("bypass_conflict_check"))

        if (!bypassAllChecks) {
            val parsedProposal = body.toProposal(
                    forceNumber = proposalNumber,
                    forceState = Proposal.State.VOTING_OPEN,
                    forceProposer = body.getUser(),
                    forceVoteThreshold = Proposal.VoteThreshold.majority(), /* temporary, is overruled with type's default */
                    forceVoteClosing = Instant.now().plus(14, ChronoUnit.DAYS),
                    forceMutability = true
            )
            proposal = parsedProposal.withVoteThreshold(parsedProposal.type.defaultVoteThreshold)
        } else {
            proposal = body.toProposal(forceNumber = proposalNumber)
        }

        if (!bypassAllChecks) {

            if (!bypassConflictCheck && proposal.hasConflicts()) {
                return badRequest("refusing to accept proposal that conflicts with existing proposal")
            }

            if (proposal.type.hasParent && proposal.parent == null) {
                return badRequest("declared proposal type needs parent but parent was not provided")
            }

            if (!proposal.type.hasParent && proposal.parent != null) {
                return badRequest("declared proposal type has no parent but parent was provided")
            }

            if (proposal.parent != null) {
                val declaredParent = ObjectPersistence.getProposal(proposal.parent!!)
                if (declaredParent == null) return badRequest("parent proposal does not exist")
                if (declaredParent.state != Proposal.State.ADOPTED) return badRequest("parent proposal is not in effect")

                if (proposal.type.requiredMutability != null && declaredParent.mutability != proposal.type.requiredMutability) return badRequest("parent proposal mutability is not compatible with declared type's required mutability")

                if (proposal.type.newMutability != null) {
                    proposal = proposal.withMutability(proposal.type.newMutability!!)
                }
            }
        }

        // We only get here if proposal is correct
        ObjectPersistence.incrementProposalNumber()

        ObjectPersistence.writeProposal(proposal)

        return success(JSONObject().put("proposal", proposal.toJSON()))
    }

    fun editProposal(urlParameters: List<String>, body: JSONObject?): Response {
        if (!UserPersistence.tokenIsAdmin(body.getToken())) return unauthorized("must be admin")
        if (urlParameters.size != 1 || urlParameters[0].toIntOrNull() == null) return badRequest("need exactly 1 url parameter of type int")
        body ?: return badRequest("need body")

        val number = urlParameters[0].toInt()

        val oldProposal = ObjectPersistence.getProposal(number)
        if (oldProposal == null) return badRequest("proposal PUT can only modify existing proposals")

        val newProposal = Proposal(
                number,
                body.stringOrNull("text") ?: oldProposal.text,
                body.intOrNull("parent") ?: oldProposal.parent,
                body.proposalTypeOrNull("type") ?: oldProposal.type,
                body.proposalStateOrNull("state") ?: oldProposal.state,
                body.stringOrNull("proposer") ?: oldProposal.proposer,
                body.voteThresholdOrNull("vote_threshold") ?: oldProposal.voteThreshold,
                body.instantOrNull("vote_closing") ?: oldProposal.voteClosing,
                body.booleanOrNull("mutable") ?: oldProposal.mutability
        )

        if (newProposal != oldProposal) ObjectPersistence.writeProposal(newProposal)

        return success()
    }

    fun getAllProposals(urlParameters: List<String>, body: JSONObject?): Response {
        val allProposals = ObjectPersistence.getAllProposals()

        val proposalJSONArray = JSONArray()
        allProposals.forEach { proposalJSONArray.put(it.toJSON()) }

        return success(JSONObject().put("proposals", proposalJSONArray))
    }
}