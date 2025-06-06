import {Timeboard} from "../types.ts";
import TimeboardRowComp from "./TimeboardRowComp.tsx";

function StationTimeboard({timeboard}: {timeboard: Timeboard | null}) {
    //Component that gives detailed information on a station, shows a timeboard if one is provided

    if (timeboard == null) {
        return null;
    }

    return (
        <>
            <div className={`h-full`}>
                <h1 className={`text-2xl ${timeboard.code.length == 3 ?
                    'text-blue-900' : 'text-amber-700'} font-bold`}>{timeboard.code}: {timeboard.name}
                    , {timeboard.admin_area}</h1>
                {timeboard.website && <p>Learn more about the station at the <a href={timeboard.website}
                                                           className={`underline text-purple-500`}>{timeboard.code} website</a></p>}
                <h2 className={`py-2 text-lg font-semibold`}>Train Schedule</h2>
                <div className={`h-2/3 lg:h-4/5 px-1 overflow-y-scroll overscroll-x-none rounded-md shadow-md bg-[#f5f9f6]`}>
                    {
                        timeboard.timeboard.map(row => <TimeboardRowComp row={row} type={timeboard.code} />)
                    }
                </div>
            </div>
        </>
    );
}

export default StationTimeboard;