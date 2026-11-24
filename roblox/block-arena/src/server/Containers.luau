-- 상자 같은 보관함. 여러 명이 동시에 열어도 내용이 같이 바뀝니다.
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)
local Inventory = require(script.Parent.Logic.Inventory)

local Containers = {}

export type Container = {
	id: number,
	title: string,
	size: number,
	slots: Inventory.Container,
	viewers: { [Player]: boolean },
}

local nextId = 0

function Containers.new(title: string, size: number): Container
	nextId += 1
	return {
		id = nextId,
		title = title,
		size = size,
		slots = {},
		viewers = {},
	}
end

function Containers.open(player: Player, container: Container)
	local state = Session.get(player)
	if not state then
		return
	end
	if state.container and state.container ~= container then
		Containers.close(player)
	end
	state.container = container
	container.viewers[player] = true
	Net.send(player, "openChest", container.id, container.title, Inventory.serializeContainer(container.slots, container.size))
end

function Containers.close(player: Player)
	local state = Session.get(player)
	if not state then
		return
	end
	local container = state.container
	if container then
		container.viewers[player] = nil
	end
	state.container = nil
	Inventory.returnCursor(state.inv)
end

function Containers.refresh(container: Container)
	local contents = Inventory.serializeContainer(container.slots, container.size)
	for player in container.viewers do
		local state = Session.get(player)
		if state and state.container == container and player.Parent then
			Net.send(player, "chest", container.id, contents)
		else
			container.viewers[player] = nil
		end
	end
end

function Containers.closeAll(container: Container)
	for player in container.viewers do
		local state = Session.get(player)
		if state and state.container == container then
			state.container = nil
			Inventory.returnCursor(state.inv)
			Net.send(player, "closeChest")
		end
	end
	table.clear(container.viewers)
end

return Containers
