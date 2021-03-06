package org.jeromq;

import java.nio.ByteBuffer;

import zmq.DecoderBase;
import zmq.Msg;

public class ZDecoder {

    public static class PersistDecoder extends DecoderBase {
        
        private enum Step {
            one_byte_size_ready,
            eight_byte_size_ready,
            flags_ready,
            message_ready
        }
        
        final private ByteBuffer tmpbuf;
        private Msg in_progress;
        

        public PersistDecoder (int bufsize_, long maxmsgsize_)
        {
            super(bufsize_, maxmsgsize_);
            
            tmpbuf = ByteBuffer.allocate(8);
            
        
            //  At the beginning, read one byte and go to one_byte_size_ready state.
            next_step (tmpbuf, 1, Step.one_byte_size_ready);
        }
        
            
        private void decoding_error ()
        {
            state(null);
        }

        @Override
        protected boolean next() {
            switch((Step)state()) {
            case one_byte_size_ready:
                return one_byte_size_ready ();
            case eight_byte_size_ready:
                return eight_byte_size_ready ();
            case flags_ready:
                return flags_ready ();
            case message_ready:
                return message_ready ();
            default:
                throw new IllegalStateException(state().toString());
            }
        }



        private boolean one_byte_size_ready() {
            //  First byte of size is read. If it is 0xff read 8-byte size.
            //  Otherwise allocate the buffer for message data and read the
            //  message data into it.
            byte first = tmpbuf.get();
            if (first == 0xff) {
                tmpbuf.clear();
                next_step (tmpbuf, 8, Step.eight_byte_size_ready);
            } else {

                //  There has to be at least one byte (the flags) in the message).
                if (first == 0) {
                    decoding_error ();
                    return false;
                }
                
                int size = (int) first;
                if (size < 0) {
                    size = (0xFF) & first;
                }

                //  in_progress is initialised at this point so in theory we should
                //  close it before calling zmq_msg_init_size, however, it's a 0-byte
                //  message and thus we can treat it as uninitialised...
                if (maxmsgsize >= 0 && (long) (size - 1) > maxmsgsize) {
                    decoding_error ();
                    return false;

                }
                else {
                    in_progress = new Msg(size-1);
                }

                tmpbuf.clear();
                next_step (tmpbuf, 1, Step.flags_ready);
            }
            return true;

        }
        
        private boolean eight_byte_size_ready() {
            //  8-byte payload length is read. Allocate the buffer
            //  for message body and read the message data into it.
            final long payload_length = tmpbuf.getLong();

            //  There has to be at least one byte (the flags) in the message).
            if (payload_length == 0) {
                decoding_error ();
                return false;
            }

            //  Message size must not exceed the maximum allowed size.
            if (maxmsgsize >= 0 && payload_length - 1 > maxmsgsize) {
                decoding_error ();
                return false;
            }

            //  Message size must fit within range of size_t data type.
            if (payload_length - 1 > Long.MAX_VALUE) {
                decoding_error ();
                return false;
            }

            final int msg_size =  (int)(payload_length - 1);
            //  in_progress is initialised at this point so in theory we should
            //  close it before calling init_size, however, it's a 0-byte
            //  message and thus we can treat it as uninitialised...
            in_progress = new Msg(msg_size);
            
            tmpbuf.clear();
            next_step (tmpbuf, 1, Step.flags_ready);
            
            return true;

        }
        
        private boolean flags_ready() {

            //  Store the flags from the wire into the message structure.
            
            byte first = tmpbuf.get();
            
            in_progress.set_flags (first);

            next_step (in_progress,
                Step.message_ready);

            return true;

        }
        
        private boolean message_ready() {
            //  Message is completely read. Push it further and start reading
            //  new message. (in_progress is a 0-byte message after this point.)
            
            boolean rc = session.write (in_progress);
            if (!rc) {
                // full
                return false;
            }
            
            tmpbuf.clear();
            next_step (tmpbuf, 1, Step.one_byte_size_ready);
            
            return true;
        }


        
        public boolean stalled ()
        {
            return state() == Step.message_ready;
        }

    }
}
