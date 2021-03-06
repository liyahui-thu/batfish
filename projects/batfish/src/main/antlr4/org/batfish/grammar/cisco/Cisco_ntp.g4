parser grammar Cisco_ntp;

import Cisco_common;

options {
   tokenVocab = CiscoLexer;
}

ntp_access_group
:
   ACCESS_GROUP
   (
      IPV4
      | IPV6
      |
      (
         PEER
         (
            name = variable
         )
      )
      |
      (
         QUERY_ONLY
         (
            name = variable
         )
      )
      |
      (
         SERVE
         (
            name = variable
         )
      )
      |
      (
         SERVE_ONLY
         (
            name = variable
         )
      )
      |
      (
         VRF vrf = variable
      )
   )+ NEWLINE
;

ntp_authenticate
:
   AUTHENTICATE NEWLINE
;

ntp_authentication
:
   AUTHENTICATION NEWLINE
;

ntp_clock_period
:
   CLOCK_PERIOD ~NEWLINE* NEWLINE
;

ntp_commit
:
   COMMIT NEWLINE
;

ntp_common
:
   ntp_access_group
   | ntp_authenticate
   | ntp_authentication
   | ntp_clock_period
   | ntp_commit
   | ntp_distribute
   | ntp_logging
   | ntp_max_associations
   | ntp_master
   | ntp_null
   | ntp_peer
   | ntp_server
   | ntp_source
   | ntp_source_interface
   | ntp_trusted_key
   | ntp_update_calendar
;

ntp_distribute
:
   DISTRIBUTE NEWLINE
;

ntp_logging
:
   LOGGING NEWLINE
;

ntp_max_associations
:
   MAX_ASSOCIATIONS DEC NEWLINE
;

ntp_master
:
   MASTER NEWLINE
;

ntp_null
:
   (
      ALLOW
      | AUTHENTICATION_KEY
      | INTERFACE
      | LOG_INTERNAL_SYNC
      | PASSIVE
   ) ~NEWLINE* NEWLINE
;

ntp_peer
:
   PEER ~NEWLINE* NEWLINE
;

ntp_server
:
   SERVER
   (
      VRF vrf = variable
   )? hostname = variable
   (
      (
         KEY key = DEC
      )
      |
      (
         MAXPOLL DEC
      )
      |
      (
         MINPOLL DEC
      )
      | prefer = PREFER
      |
      (
         SOURCE
         (
            src_interface = interface_name
            | src_interface_alias = variable
         )
      )
      |
      (
         USE_VRF vrf = variable
      )
      |
      (
         VERSION ver = DEC
      )
   )* NEWLINE
;

ntp_source
:
   SOURCE ~NEWLINE* NEWLINE
;

ntp_source_interface
:
   SOURCE_INTERFACE iname = interface_name NEWLINE
;

ntp_trusted_key
:
   TRUSTED_KEY DEC NEWLINE
;

ntp_update_calendar
:
   UPDATE_CALENDAR ~NEWLINE* NEWLINE
;

s_ntp
:
   NO? NTP
   (
      ntp_common
      |
      (
         NEWLINE ntp_common*
      )
   )
;

