package web.object_management

import model.Proposal
import org.json.JSONObject
import java.time.Instant

internal fun Proposal.toJSON(): JSONObject {
    return JSONObject()
        .put("number", number)
        .put("text", text)
        .put("parent", parent ?: JSONObject.NULL)
        .put("type", type.externalName)
        .put("state", state.externalName)
        .put("proposer", proposer)
        .put("vote_threshold", voteThreshold.specifier())
        .put("vote_closing", voteClosing)
        .put("mutable", mutability)
}

internal fun JSONObject.toProposal(
        forceNumber: Int = getInt("number"),
        forceState: Proposal.State = Proposal.State.fromExternalName(getString("state")),
        forceProposer: String? = getString("proposer"),
        forceVoteThreshold: Proposal.VoteThreshold = Proposal.VoteThreshold.parseBySpec(getString("vote_threshold")),
        forceVoteClosing: Instant? = Instant.parse(getString("vote_closing")),
        forceMutability: Boolean = getBoolean("mutable")
): Proposal {
    return Proposal(
            forceNumber,
            getString("text"),
            if (isNull("parent")) null else getInt("parent"),
            Proposal.Type.fromExternalName(getString("type")),
            forceState,
            forceProposer,
            forceVoteThreshold,
            forceVoteClosing,
            forceMutability
    )
}

internal fun JSONObject.intOrNull(key: String): Int? = if (has(key)) tryOrNull { getInt(key) } else null

private inline fun <T> tryOrNull(block: () -> T): T? = try { block() } catch (e: Exception) { null }

internal fun JSONObject.stringOrNull(key: String): String? = optString(key, null)
internal fun JSONObject.booleanOrNull(key: String): Boolean? = if (has(key)) tryOrNull { getBoolean(key) } else null

internal fun JSONObject.proposalStateOrNull(key: String) = tryOrNull { Proposal.State.fromExternalName(key) }
internal fun JSONObject.proposalTypeOrNull(key: String) = tryOrNull { Proposal.Type.fromExternalName(key) }

internal fun JSONObject.voteThresholdOrNull(key: String) = tryOrNull { Proposal.VoteThreshold.parseBySpec(optString(key)!!) }
internal fun JSONObject.instantOrNull(key: String) = tryOrNull { Instant.parse(stringOrNull(key)) }