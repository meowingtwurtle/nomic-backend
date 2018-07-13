package persistence

import model.Proposal
import model.Proposal.*
import model.withNewState
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object ObjectPersistence : DBUser() {
    private lateinit var storeProposalStatement: PreparedStatement
    private lateinit var getProposalStatement: PreparedStatement
    private lateinit var storeVoteStatement: PreparedStatement
    private lateinit var getAllProposalVotesStatement: PreparedStatement
    private lateinit var getVoteStatement: PreparedStatement
    private lateinit var countVoteStatement: PreparedStatement
    private lateinit var getUserVotesStatement: PreparedStatement

    private val proposalMap = mutableMapOf<Int, Proposal>()
    private val voteMap = mutableMapOf<Int, MutableMap<String, Vote>>()
    private val voteCounts = mutableMapOf<Int, MutableMap<Vote, Int>>()

    private const val DEFAULT_PROPOSAL_NUMBER = 301
    private val nextProposalNumber = AtomicInteger(DEFAULT_PROPOSAL_NUMBER)

    private val scheduledExecutorService = ScheduledThreadPoolExecutor(1)

    override fun init() {
        ensureTablesExist()
        initPreparedStatements()

        setupProposalCount()
    }

    private fun ensureTablesExist() {
        connection().createStatement()
            .execute("CREATE TABLE IF NOT EXISTS proposals(number BIGINT PRIMARY KEY NOT NULL, proposer VARCHAR(32) NOT NULL, parent BIGINT, text VARCHAR(4096), type VARCHAR(32) NOT NULL, state VARCHAR(32) NOT NULL, vote_threshold VARCHAR(64) NOT NULL, vote_closing TIMESTAMP, mutable BOOLEAN NOT NULL)")
        connection().createStatement()
            .execute("CREATE TABLE IF NOT EXISTS votes(voter VARCHAR(32) NOT NULL, proposal BIGINT NOT NULL, vote VARCHAR(16) NOT NULL)")
    }

    private fun initPreparedStatements() {
        getProposalStatement = connection().prepareStatement("SELECT * FROM proposals WHERE number = ?")
        storeVoteStatement = connection().prepareStatement("MERGE INTO votes AS oldvals USING (VALUES(?,?,?)) AS newvals(voter,proposal,vote) ON oldvals.proposal = newvals.proposal AND oldvals.voter = newvals.voter WHEN MATCHED THEN UPDATE SET oldvals.vote = newvals.vote WHEN NOT MATCHED THEN INSERT VALUES newvals.voter, newvals.proposal, newvals.vote")
        getAllProposalVotesStatement = connection().prepareStatement("SELECT voter, vote FROM votes WHERE proposal = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)
        getVoteStatement = connection().prepareStatement("SELECT vote FROM votes WHERE voter = ? AND proposal = ?")
        countVoteStatement = connection().prepareStatement("SELECT COUNT(voter) FROM votes WHERE proposal = ? AND vote = ?")
        storeProposalStatement = connection().prepareStatement("MERGE INTO proposals AS oldvals USING (VALUES(?,?,?,?,?,?,?,?,?)) AS newvals(number,proposer,parent,text,type,state,vote_threshold,vote_closing,mutable) ON oldvals.number = newvals.number WHEN MATCHED THEN UPDATE SET oldvals.number = newvals.number, oldvals.proposer = newvals.proposer, oldvals.parent = newvals.parent, oldvals.text = newvals.text, oldvals.type = newvals.type, oldvals.state = newvals.state, oldvals.vote_threshold = newvals.vote_threshold, oldvals.vote_closing = newvals.vote_closing, oldvals.mutable = newvals.mutable WHEN NOT MATCHED THEN INSERT VALUES newvals.number, newvals.proposer, newvals.parent, newvals.text, newvals.type, newvals.state, newvals.vote_threshold, newvals.vote_closing, newvals.mutable")
        getUserVotesStatement = connection().prepareStatement("SELECT proposal, vote FROM votes WHERE voter = ?")
    }

    private fun setupProposalCount() {
        val statement = connection().createStatement()
        val resultSet = statement.executeQuery("SELECT MAX(number) FROM proposals")

        if (resultSet == null || !resultSet.next()) {
            nextProposalNumber.set(DEFAULT_PROPOSAL_NUMBER)
            return
        }

        val largestExistingProposalNumber = resultSet.getInt(1)

        if (largestExistingProposalNumber >= DEFAULT_PROPOSAL_NUMBER) nextProposalNumber.set(largestExistingProposalNumber + 1)
        else nextProposalNumber.set(DEFAULT_PROPOSAL_NUMBER)
    }

    fun loadProposalVotes(proposal: Int): Map<String, Vote> = read {
        run {
            val mapValue = voteMap[proposal]
            if (mapValue != null) return@read HashMap(mapValue)
        }

        getAllProposalVotesStatement.setInt(1, proposal)
        getAllProposalVotesStatement.execute()
        getAllProposalVotesStatement.clearParameters()
        val resultSet = getAllProposalVotesStatement.resultSet!!

        val newMap = mutableMapOf<String, Vote>()
        while (resultSet.next()) {
            newMap[resultSet.getString("voter")] = Vote.valueOf(resultSet.getString("vote"))
        }

        voteMap[proposal] = newMap

        return@read HashMap(newMap)
    }

    fun getProposalVote(proposal: Int, voter: String) = loadProposalVotes(proposal)[voter]

    fun setProposalVote(proposal: Int, voter: String, vote: Vote) = write {
        loadProposalVotes(proposal) // Loads proposal votes into memory

        voteMap[proposal]!![voter] = vote

        storeVoteStatement.setString(1, voter)
        storeVoteStatement.setInt(2, proposal)
        storeVoteStatement.setString(3, vote.toString())
        storeVoteStatement.execute()
        storeVoteStatement.clearParameters()
    }

    fun getProposal(number: Int): Proposal? = read {
        if (proposalMap.containsKey(number)) return@read proposalMap[number]!!

        getProposalStatement.setInt(1, number)
        getProposalStatement.execute()
        getProposalStatement.clearParameters()
        val resultSet = getProposalStatement.resultSet
        if (!resultSet.next()) return@read null

        val newProposal = resultSet.toProposal()
        cacheProposal(newProposal)

        return@read newProposal
    }

    private fun ResultSet.toProposal(): Proposal {
        val parent = (getObject("parent") as Number?)?.toInt()

        return Proposal(
                getInt("number"),
                getString("text"),
                parent,
                Type.valueOf(getString("type")),
                State.valueOf(getString("state")),
                getString("proposer"),
                VoteThreshold.parseBySpec(getString("vote_threshold")),
                getTimestamp("vote_closing").toInstant(),
                getBoolean("mutable")
        )
    }

    fun writeProposal(proposal: Proposal) = write {
        proposalMap[proposal.number] = proposal

        storeProposalStatement.setInt(1, proposal.number)
        storeProposalStatement.setString(2, proposal.proposer)

        if (proposal.parent != null)
            storeProposalStatement.setInt(3, proposal.parent)
        else
            storeProposalStatement.setNull(3, java.sql.Types.INTEGER)

        storeProposalStatement.setString(4, proposal.text)
        storeProposalStatement.setString(5, proposal.type.toString())
        storeProposalStatement.setString(6, proposal.state.toString())
        storeProposalStatement.setString(7, proposal.voteThreshold.specifier())
        storeProposalStatement.setTimestamp(8, Timestamp.from(proposal.voteClosing))
        storeProposalStatement.setBoolean(9, proposal.mutability)

        storeProposalStatement.execute()
        storeProposalStatement.clearParameters()
    }

    fun proposalVoteCount(proposal: Int): Map<Vote, Int> {
        loadProposalVotes(proposal)

        val map = voteMap[proposal]!!

        var ayes: Int = 0
        var noes: Int = 0

        for (entry in map) {
            when (entry.value) {
                Proposal.Vote.IN_FAVOR -> ayes++
                Proposal.Vote.OPPOSE -> noes++
                Proposal.Vote.ABSTAIN -> {}
                else -> TODO("unimplemented vote type")
            }
        }

        return mapOf(Vote.IN_FAVOR to ayes, Vote.OPPOSE to noes)
    }

    // TODO: real multithreading support
    fun currentProposalNumber(): Int = read {
        return@read nextProposalNumber.get()
    }

    fun incrementProposalNumber() = write {
        nextProposalNumber.getAndIncrement()
    }

    fun loadAllProposals() = getAllProposals()

    fun getAllProposals(): List<Proposal> = read {
        val resultSet = connection().createStatement().executeQuery("SELECT * FROM proposals")
        resultSet ?: return@read emptyList()

        val proposalList = mutableListOf<Proposal>()

        while (resultSet.next()) {
            val proposal = resultSet.toProposal()
            proposalList.add(proposal)
            cacheProposal(proposal)
        }

        return@read proposalList
    }

    private fun cacheProposal(proposal: Proposal) {
        proposalMap[proposal.number] = proposal

        if (proposal.state == State.VOTING_OPEN && proposal.voteClosing != null) {
            scheduledExecutorService.schedule({
                closeVoting(proposal.number)
            }, proposal.voteClosing.toEpochMilli() - Instant.now().toEpochMilli(), TimeUnit.MILLISECONDS)
        }
    }

    private fun closeVoting(proposalNumber: Int) = write {
        val proposal = getProposal(proposalNumber)!!

        if (proposal.state != State.VOTING_OPEN) return@write

        val votes = proposalVoteCount(proposal.number)

        val totalPeople = UserPersistence.userCount()

        val ayes = votes[Vote.IN_FAVOR]!!
        val noes = votes[Vote.OPPOSE]!!
        val abstentions = totalPeople - ayes - noes

        val passes = proposal.voteThreshold.passesOnClosing(ayes, noes, abstentions)

        val newState = if (passes) State.ADOPTED else State.REJECTED

        val newProposal = proposal.withNewState(newState)

        writeProposal(newProposal)
    }

    fun getUserVotes(username: String): Map<Int, Proposal.Vote> = read {
        getUserVotesStatement.setString(1, username)
        getUserVotesStatement.execute()
        getUserVotesStatement.clearParameters()

        val resultSet = getUserVotesStatement.resultSet!!

        val map = mutableMapOf<Int, Proposal.Vote>()

        while (resultSet.next()) {
            map[resultSet.getInt("proposal")] = Proposal.Vote.valueOf(resultSet.getString("vote"))
        }

        return@read map
    }
}