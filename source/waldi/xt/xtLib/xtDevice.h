#pragma once

#include "xt.h"
#include "xtState.h"
#include "xtSysexRemoteControl.h"
#include "xtWavePreview.h"
#include "wLib/wDevice.h"

namespace dsp56k
{
	class EsxiClock;
}

namespace xt
{
	class Device : public wLib::Device
	{
	public:
		Device(const synthLib::DeviceCreateParams& _params);

		float getSamplerate() const override;
		bool isValid() const override;
		bool getState(std::vector<uint8_t>& _state, synthLib::StateType _type) override;
		bool setState(const std::vector<uint8_t>& _state, synthLib::StateType _type) override;
		uint32_t getChannelCountIn() override;
		uint32_t getChannelCountOut() override;

		bool canModifyDspClock() const override { return true; }

        // Audio/control thread only, serialized with process(). Bypasses State's
        // optimistic editor cache; the revision advances only on hardware RX.
        void requestSingleFromFirmware();
        uint64_t getFirmwareSingleRevision() const { return m_firmwareSingleRevision; }
        const std::vector<uint8_t>& getFirmwareSingle() const { return m_firmwareSingle; }

	protected:
		void readMidiOut(std::vector<synthLib::SMidiEvent>& _midiOut) override;
		void processAudio(const synthLib::TAudioInputs& _inputs, const synthLib::TAudioOutputs& _outputs, size_t _samples) override;
		bool sendMidi(const synthLib::SMidiEvent& _ev, std::vector<synthLib::SMidiEvent>& _response) override;

		dsp56k::EsxiClock* getDspEsxiClock() const override;
	private:

        std::vector<uint8_t> m_firmwareSingle;
        uint64_t m_firmwareSingleRevision = 0;
		Xt m_xt;
		WavePreview m_wavePreview;
		State m_state;
		SysexRemoteControl m_sysexRemote;
	};
}
