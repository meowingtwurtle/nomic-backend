package web.object_management

import model.Proposal
import org.json.JSONArray
import org.json.JSONObject
import persistence.ObjectPersistence
import web.Response
import web.success

object StandingRequestHandler {
    fun getStandingRules(urlParameters: List<String>, body: JSONObject?): Response {
        val proposalList = ObjectPersistence.getAllProposals()

        val standingProposals = mutableMapOf<Int, Proposal>() // Map for easy removal

        for (proposal in proposalList.filter { it.state == Proposal.State.ADOPTED }) {
            // TODO: Use the Type properties
            when (proposal.type) {
                Proposal.Type.ENACTMENT -> standingProposals[proposal.number] = proposal
                Proposal.Type.REPEAL -> standingProposals.remove(proposal.parent!!)
                Proposal.Type.AMENDMENT, Proposal.Type.MAKE_MUTABLE, Proposal.Type.MAKE_IMMUTABLE -> {
                    standingProposals.remove(proposal.parent!!)
                    standingProposals[proposal.number] = proposal
                }
                else -> TODO("unimplemented proposal type")
            }
        }

        val proposalJSONArray = JSONArray()

        standingProposals.values.forEach {
            val ruleObject = JSONObject()
                .put("number", it.number)
                .put("mutable", it.mutability)

            var currentProposal = it
            while (!currentProposal.type.hasText) { currentProposal = ObjectPersistence.getProposal(currentProposal.parent!!)!! }

            ruleObject.put("text", currentProposal.text)

            proposalJSONArray.put(ruleObject)
        }

        return success(JSONObject().put("standing_rules", proposalJSONArray))
    }
}