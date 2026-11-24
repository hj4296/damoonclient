-- 맵 투표 집계. 가장 많은 표를 받은 맵, 동점이면 그중에서 무작위로 고릅니다.
local Vote = {}

function Vote.counts(votes: { [any]: string }, mapIds: { string }): { [string]: number }
	local counts = {}
	for _, id in mapIds do
		counts[id] = 0
	end
	for _, id in votes do
		if counts[id] ~= nil then
			counts[id] += 1
		end
	end
	return counts
end

function Vote.winner(votes: { [any]: string }, mapIds: { string }, random: () -> number): string
	local counts = Vote.counts(votes, mapIds)
	local best = -1
	local top = {}
	for _, id in mapIds do
		local n = counts[id]
		if n > best then
			best = n
			top = { id }
		elseif n == best then
			table.insert(top, id)
		end
	end
	local index = 1 + math.floor(random() * #top)
	return top[math.clamp(index, 1, #top)]
end

return Vote
