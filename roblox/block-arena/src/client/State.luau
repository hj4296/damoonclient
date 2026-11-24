-- 클라이언트가 알고 있는 현재 상태 (가방, 어디에 있는지, 투표 정보 등)
local State = {}

export type Stack = { id: string, count: number }

State.slots = {} :: { [number]: Stack? }
State.armor = {} :: { [string]: string? }
State.selected = 1
State.cursor = nil :: Stack?
State.armorLocked = false
State.context = { location = "lobby" } :: { [string]: any }
State.vote = nil :: any
State.lobbyInfo = {} :: { [string]: { waiting: number, playing: number } }
State.shop = nil :: any
State.chest = nil :: { id: number, title: string, slots: { [number]: Stack? } }?
State.passes = {} :: { [string]: { owned: boolean, price: number, available: boolean, studioTest: boolean } }
State.settings = {} :: { [string]: any } -- Main 에서 기본값으로 채움
State.skinCode = "" -- 저장된 내 스킨

local listeners: { [string]: { () -> () } } = {}

function State.listen(kind: string, fn: () -> ())
	listeners[kind] = listeners[kind] or {}
	table.insert(listeners[kind], fn)
end

function State.emit(kind: string)
	for _, fn in listeners[kind] or {} do
		local ok, err = pcall(fn)
		if not ok then
			warn("[블록 아레나] 화면 갱신 오류 (" .. kind .. "):", err)
		end
	end
end

-- 서버가 보낸 { ["1"] = { id, count } } 꼴을 숫자 칸으로 바꿉니다.
function State.decode(raw: { [string]: { any } }?): { [number]: Stack? }
	local out = {}
	for key, value in raw or {} do
		local index = tonumber(key)
		if index then
			out[index] = { id = value[1], count = value[2] }
		end
	end
	return out
end

function State.applyInventory(data)
	State.slots = State.decode(data.slots)
	State.armor = data.armor or {}
	State.selected = data.selected or 1
	State.cursor = if data.cursor then { id = data.cursor[1], count = data.cursor[2] } else nil
	State.armorLocked = data.armorLocked == true
	State.emit("inv")
end

function State.selectedStack(): Stack?
	return State.slots[State.selected]
end

return State
