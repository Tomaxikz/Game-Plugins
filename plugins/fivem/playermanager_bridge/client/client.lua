-- Player Manager Bridge client script: the only component that runs client natives (games/fivem.md 8.3).
-- Every server payload is range-checked; nothing here touches inventory or job data.
local IS_GTA = GetGameName() == 'gta5'
local REPORT_MS = 1000
local lastVehicle = nil
local lastReport = 0

local function num(v, min, max)
    return type(v) == 'number' and v == v and v >= min and v <= max
end

local function ack(req, ok, err)
    if type(req) ~= 'number' then return end
    TriggerServerEvent('pm:server:ack', req, ok, err)
end

local function run(req, fn)
    local ok, err = pcall(fn)
    if ok then ack(req, true, nil) else ack(req, false, tostring(err):sub(1, 200)) end
end

RegisterNetEvent('pm:client:teleport', function(p)
    if type(p) ~= 'table' then return end
    run(p.req, function()
        if not (num(p.x, -20000, 20000) and num(p.y, -20000, 20000) and num(p.z, -2000, 5000) and num(p.heading, 0, 360)) then
            error('invalid coordinates')
        end
        local ped = PlayerPedId()
        local veh = GetVehiclePedIsIn(ped, false)
        local target = ped
        if veh ~= 0 and GetPedInVehicleSeat(veh, -1) == ped then target = veh end
        SetEntityCoords(target, p.x + 0.0, p.y + 0.0, p.z + 0.0, false, false, false, true)
        SetEntityHeading(target, p.heading + 0.0)
    end)
end)

RegisterNetEvent('pm:client:heal', function(p)
    if type(p) ~= 'table' then return end
    run(p.req, function()
        if not num(p.health, 0, 1000) then error('invalid health') end
        SetEntityHealth(PlayerPedId(), math.floor(p.health))
    end)
end)

RegisterNetEvent('pm:client:set_armour', function(p)
    if type(p) ~= 'table' then return end
    run(p.req, function()
        if not IS_GTA then error('set_armour is not available on this game') end
        if not num(p.amount, 0, 100) then error('invalid armour') end
        SetPedArmour(PlayerPedId(), math.floor(p.amount))
    end)
end)

RegisterNetEvent('pm:client:revive', function(p)
    if type(p) ~= 'table' then return end
    run(p.req, function()
        local ev = p.event
        if type(ev) ~= 'string' or not ev:match('^[%w_:%.%-]+$') or #ev > 128 then error('invalid revive event') end
        TriggerServerEvent(ev)
    end)
end)

RegisterNetEvent('pm:client:notify', function(p)
    if type(p) ~= 'table' then return end
    run(p.req, function()
        if type(p.text) ~= 'string' or #p.text > 256 then error('invalid text') end
        if IS_GTA then
            BeginTextCommandThefeedPost('STRING')
            AddTextComponentSubstringPlayerName(p.text)
            EndTextCommandThefeedPostTicker(false, false)
        else
            TriggerEvent('chat:addMessage', { args = { 'Panel', p.text } })
        end
    end)
end)

-- Vehicle report: seat and body health are not readable on the server (games/fivem.md 5.2).
-- RedM has no vehicle report: no thread at all. On GTA the loop runs at 250 ms in a vehicle and 500 ms on foot.
if IS_GTA then CreateThread(function()
    while true do
        Wait(lastVehicle and 250 or 500)
        if IS_GTA then
            local ped = PlayerPedId()
            local veh = GetVehiclePedIsIn(ped, false)
            local now = GetGameTimer()
            if veh ~= 0 then
                local seat = nil
                local maxSeats = GetVehicleMaxNumberOfPassengers(veh)
                for s = -1, maxSeats - 1 do
                    if not IsVehicleSeatFree(veh, s) and GetPedInVehicleSeat(veh, s) == ped then seat = s break end
                end
                if seat and (veh ~= lastVehicle or now - lastReport >= REPORT_MS) then
                    TriggerServerEvent('pm:server:state', { seat = seat, vehicle_health = GetVehicleBodyHealth(veh) + 0.0 })
                    lastVehicle = veh
                    lastReport = now
                end
            elseif lastVehicle then
                TriggerServerEvent('pm:server:state', { seat = nil })
                lastVehicle = nil
            end
        end
    end
end) end
