#include "XeniaDispatch.h"
#include <iostream>
#include <memory_resource>
#include <stdexcept>

using rixenia::Bytes;
static void check(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
static Bytes patch(unsigned program, unsigned cutoff) {
    Bytes p(265, 0); p[0]=0xf0; p[1]=0x3e; p[2]=0x0e; p[3]=0x7f;
    p[4]=0x10; p[6]=static_cast<unsigned char>(program); p[7+62]=static_cast<unsigned char>(cutoff);
    p.back()=0xf7; p[263]=rixenia::checksum(p,7); return p;
}
int main() {
    using D=rixenia::MidiDispatch; D d;
    // Old `if(channel)...else if(sysex)` never sends any dump in this case.
    int controls=0, channels=0;
    for(int i=0;i<400;++i) {
        auto next=d.choose(true,true,0);
        controls += next==D::Next::Control; channels += next==D::Next::Channel;
    }
    check(controls==200 && channels==200,"Continuous MIDI starved a queue");
    d.reset(); check(d.choose(true,true,500)==D::Next::None,"Cooldown bypassed");
    check(d.choose(true,true,0)==D::Next::Channel,"First note batch lost");
    check(d.choose(true,true,100)==D::Next::None,"Partial SysEx interrupted");
    check(d.choose(true,true,0)==D::Next::Control,"Patch not serviced after one channel batch");
    check(d.choose(false,true,0)==D::Next::Control,"Control stalled without MIDI");
    check(d.choose(false,false,0)==D::Next::None,"Idle queue produced work");
    for(int block:{256,512,1024}) {
        d.reset(); int waited=0;
        while(d.choose(true,true,0)!=D::Next::Control) waited+=block;
        check(waited<=block,"Patch wait not bounded to one eligible audio block");
    }
    rixenia::PatchChange change;
    auto a=patch(0,40),b=patch(1,100);
    change.request(a,1000); auto ga=change.generation();
    check(change.phase(1001)==1,"Intent shown as active");
    check(!change.confirm(rixenia::singleEdit(a),ga,1002),"Unsent cached patch falsely acknowledged");
    std::pmr::vector<std::uint8_t> nativeBuffer(a.begin(), a.end());
    change.dispatched(nativeBuffer); check(change.phase(1002)==2,"Dispatch state missing");
    change.request(b,1010); auto gb=change.generation();
    change.dispatched(a); check(!change.sent(),"Obsolete dump marked latest patch sent");
    change.dispatched(b);
    check(!change.confirm(rixenia::singleEdit(a),ga,1020),"Stale response acknowledged a newer patch");
    check(!change.confirm(rixenia::singleEdit(b),ga,1020),"Old query acknowledged a newer request");
    check(!change.confirm(rixenia::singleEdit(a),gb,1020),"Same-name different sound falsely matched");
    check(!change.confirm(b,gb,1020),"Bank dump mistaken for current edit buffer");
    check(change.phase(4010)==3 && change.pending(),"Timeout falsely marked patch active");
    check(change.confirm(rixenia::singleEdit(b),gb,4020),"Late valid firmware response not accepted");
    check(change.phase(4020)==0 && change.lastMs()==3010,"Confirmation timing wrong");
    change.request(a,5000); change.reset();
    check(!change.pending() && change.lastMs()==-1,"Reboot retained stale transaction");
    std::cout<<"Xenia dispatch: continuous MIDI fairness, pacing, all buffer sizes, latest-wins, stale/cache rejection, timeout and firmware confirmation passed\n";
}
