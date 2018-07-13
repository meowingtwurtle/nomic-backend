package web.object_management

import model.Proposal
import model.closeWithState
import org.json.JSONArray
import org.json.JSONObject
import persistence.ObjectPersistence
import persistence.UserPersistence
import web.*

object VoteRequestHandler {
    fun getVote(urlParameters: List<String>, body: JSONObject?): Response {
        if (urlParameters.isEmpty()) return badRequest("expected 2 url parameters, the first being \"user\" or a proposal number")

        if (urlParameters[0] == "user") {
            if (urlParameters.size < 2) return badRequest("expected 2 url parameters for \"user\", user and username.")
            return getUserVotes(urlParameters[1])
        } else if (urlParameters[0].toIntOrNull() != null) {
            if (urlParameters.size < 2) return badRequest("need more url parameters, \"user\" and username, or \"count\"")

            val proposalNumber = urlParameters[0].toInt()

            return when(urlParameters[1].toLowerCase()) {
                "user" -> getUserVote(proposalNumber, urlParameters[2])
                "count" -> getVoteCount(proposalNumber)
                else -> badRequest("url parameter 1 should be \"user\" or \"count\"")
            }
        } else {
            return badRequest("expected 2 url parameters, the first being \"user\" or a proposal number")
        }


//        if (urlParameters.size < 2) return badRequest("request should be to url /vote/<proposal #>/<user/count>/...")
//        if (urlParameters[0].toIntOrNull() == null) return badRequest("url parameter 0 should be of type int (proposal #)")
//
//        val proposalNumber = urlParameters[0].toInt()
//
//        return when (urlParameters[1].toLowerCase()) {
//            "user" -> getUserVote(proposalNumber, urlParameters[2])
//            "count" -> getVoteCount(proposalNumber)
//            else -> badRequest("""url parameter 1 should be "user" or "count"""")
//        }
    }

    private fun getUserVotes(username: String): Response {
        val votes = ObjectPersistence.getUserVotes(username)

        val votesObject = JSONObject()
        val votedOnArray = JSONArray()


        for (entry in votes) {
            votesObject.put(entry.key.toString(), entry.value.externalName)
            votedOnArray.put(entry.key)
        }

        val responseJSON = JSONObject().put("votes", votesObject).put("voted_on", votedOnArray)
        return success(responseJSON)
    }

    fun setVote(urlParameters: List<String>, body: JSONObject?): Response {
        if (body == null) return badRequest("need body")
        if (!body.isAuthorized()) return unauthorized()
        if (urlParameters.size != 1 || urlParameters[0].toIntOrNull() == null) return badRequest("need 1 url parameter of type int (proposal)")

        val proposalNumber = urlParameters[0].toInt()

        val proposal = ObjectPersistence.getProposal(proposalNumber)

        if (proposal == null) return badRequest("proposal does not exist")
        if (!body.has("vote")) return badRequest("need vote value in body")

        val jsonResponse = JSONObject().put("state", proposal.state.externalName)

        if (proposal.state != Proposal.State.VOTING_OPEN) {
            jsonResponse.put("reason", "proposal is not open for voting")

            badRequest(jsonObject = jsonResponse)
        }

        val vote = Proposal.Vote.fromExternalName(body.getString("vote"))

        ObjectPersistence.setProposalVote(proposalNumber, body.getUser()!!, vote)

        val newState = handleEarlyClosing(proposal)

        jsonResponse.put("state", (newState ?: proposal.state).externalName)

        if (newState != null) {
            jsonResponse.put("closed", true)
        } else {
            jsonResponse.put("closed", false)
        }

        return success(jsonResponse)
    }

    private fun getVoteCount(proposalNumber: Int): Response {
        if (ObjectPersistence.getProposal(proposalNumber) == null) return notFound("proposal not found")

        val votes = ObjectPersistence.proposalVoteCount(proposalNumber)

        val json = JSONObject()

        for (entry in votes) {
            json.put(entry.key.externalName, entry.value)
        }

        return success(json)
    }

    private fun getUserVote(proposalNumber: Int, username: String): Pair<Int, JSONObject?> {
        if (ObjectPersistence.getProposal(proposalNumber) == null) return notFound("proposal does not exist")
        if (!UserPersistence.userExists(username)) return notFound("user does not exist")

        val rawVote = ObjectPersistence.getProposalVote(proposalNumber, username)
        val encodedVote = (rawVote ?: Proposal.Vote.ABSTAIN).externalName
        return success(JSONObject().put("vote", encodedVote))
    }

    private fun handleEarlyClosing(proposal: Proposal): Proposal.State? {
        val totalUsers = UserPersistence.userCount()
        val votes = ObjectPersistence.proposalVoteCount(proposal.number)

        val ayes = votes[Proposal.Vote.IN_FAVOR]!!
        val noes = votes[Proposal.Vote.OPPOSE]!!
        val abstentions = totalUsers - ayes - noes

        val passesEarly = proposal.voteThreshold.passesPreemptively(ayes, noes, abstentions)
        val failsEarly = proposal.voteThreshold.failsPreemptively(ayes, noes, abstentions)

        if (passesEarly && failsEarly) throw IllegalStateException("Proposal both passes and closes preemptively: #${proposal.number}, threshold: ${proposal.voteThreshold.specifier()}")
        if (!(passesEarly || failsEarly)) return null

        val newState = if (passesEarly) Proposal.State.ADOPTED else Proposal.State.REJECTED

        proposal.closeWithState(newState)

        return newState
    }
}